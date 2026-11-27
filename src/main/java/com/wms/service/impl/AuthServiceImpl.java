package com.wms.service.impl;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wms.common.BizException;
import com.wms.dto.LoginDTO;
import com.wms.dto.RegisterDTO;
import com.wms.entity.SysMenu;
import com.wms.entity.SysRole;
import com.wms.entity.SysRoleMenu;
import com.wms.entity.SysUser;
import com.wms.entity.SysUserRole;
import com.wms.service.AuthService;
import com.wms.service.SysMenuService;
import com.wms.service.SysRoleMenuService;
import com.wms.service.SysRoleService;
import com.wms.service.SysUserRoleService;
import com.wms.service.SysUserService;
import com.wms.utils.JwtUtils;
import com.wms.utils.UserContext;
import com.wms.vo.LoginVO;
import com.wms.vo.MenuVO;
import com.wms.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 认证服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserService userService;
    private final SysRoleService roleService;
    private final SysUserRoleService userRoleService;
    private final SysMenuService menuService;
    private final SysRoleMenuService roleMenuService;
    private final JwtUtils jwtUtils;

    /** 注册后默认角色：只读访客，禁止通过注册自授 OPERATOR/ADMIN */
    private static final String DEFAULT_REGISTER_ROLE = "GUEST";

    /** 验证码缓存：key -> (code, 过期时间戳)，有效期 5 分钟，用后即删 */
    private static final Map<String, CaptchaEntry> CAPTCHA_CACHE = new ConcurrentHashMap<>();

    private record CaptchaEntry(String code, long expireAt) {
    }

    /** 连续失败锁定阈值与锁定时长 */
    private static final int MAX_FAIL_COUNT = 5;
    private static final int LOCK_MINUTES = 10;

    @Override
    public Map<String, String> captcha() {
        LineCaptcha lineCaptcha = CaptchaUtil.createLineCaptcha(120, 40, 4, 20);
        String key = IdUtil.simpleUUID();
        CAPTCHA_CACHE.put(key, new CaptchaEntry(lineCaptcha.getCode(), System.currentTimeMillis() + 5 * 60 * 1000));
        // 开发期方便联调，正式部署可删除本行日志
        log.info("【开发期】图形验证码 {} = {}", key, lineCaptcha.getCode());
        Map<String, String> map = new HashMap<>();
        map.put("key", key);
        map.put("img", lineCaptcha.getImageBase64Data());
        return map;
    }

    @Override
    public LoginVO login(LoginDTO dto, String ip) {
        // 1. 校验验证码（一次性）
        CaptchaEntry entry = CAPTCHA_CACHE.remove(dto.getKey());
        if (entry == null || entry.expireAt() < System.currentTimeMillis()
                || !entry.code().equalsIgnoreCase(dto.getCode())) {
            throw new BizException("验证码错误或已过期");
        }
        // 2. 查询用户
        SysUser user = userService.getOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        if (user == null) {
            throw new BizException("账号或密码错误");
        }
        if (user.getStatus() == 0) {
            throw new BizException("账号已被禁用，请联系管理员");
        }
        // 3. 锁定校验：连续失败 5 次锁定 10 分钟
        if (user.getLoginFailCount() != null && user.getLoginFailCount() >= MAX_FAIL_COUNT
                && user.getLockTime() != null
                && user.getLockTime().isAfter(LocalDateTime.now().minusMinutes(LOCK_MINUTES))) {
            throw new BizException("密码连续错误次数过多，账号已锁定，请" + LOCK_MINUTES + "分钟后再试");
        }
        // 4. 密码校验（BCrypt）
        if (!BCrypt.checkpw(dto.getPassword(), user.getPassword())) {
            int fails = (user.getLoginFailCount() == null ? 0 : user.getLoginFailCount()) + 1;
            user.setLoginFailCount(fails);
            if (fails >= MAX_FAIL_COUNT) {
                user.setLockTime(LocalDateTime.now());
            }
            userService.updateById(user);
            throw new BizException(fails >= MAX_FAIL_COUNT
                    ? "密码连续错误" + MAX_FAIL_COUNT + "次，账号已锁定" + LOCK_MINUTES + "分钟"
                    : "账号或密码错误");
        }
        // 5. 登录成功：重置失败记录、记录登录 IP 与时间、签发 Token
        user.setLoginFailCount(0);
        user.setLockTime(null);
        user.setLastLoginTime(LocalDateTime.now());
        user.setLastLoginIp(ip);
        userService.updateById(user);

        List<String> roles = listRoleCodes(user.getId());
        String token = jwtUtils.createToken(user.getId(), user.getUsername());

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUser(toUserVO(user, roles));
        vo.setRoles(roles);
        vo.setMenus(listMenusByRoles(roles));
        return vo;
    }

    @Override
    public void register(RegisterDTO dto) {
        long count = userService.count(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BizException("该账号已被注册");
        }
        SysUser user = new SysUser();
        BeanUtil.copyProperties(dto, user);
        user.setNickname(dto.getRealName());
        user.setPassword(BCrypt.hashpw(dto.getPassword()));
        user.setStatus(1);
        try {
            userService.save(user);
        } catch (DuplicateKeyException e) {
            // 并发注册同名账号：由唯一键兜底，返回业务提示而不是 500
            throw new BizException("该账号已被注册");
        }

        // 注册成功只分配默认角色 GUEST（P1-7）：注册入口不得自授 OPERATOR/ADMIN
        SysRole guest = roleService.getOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, DEFAULT_REGISTER_ROLE));
        if (guest != null) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(user.getId());
            ur.setRoleId(guest.getId());
            userRoleService.save(ur);
        }
    }

    @Override
    public LoginVO userinfo() {
        Long userId = UserContext.getUserId();
        SysUser user = userService.getById(userId);
        if (user == null) {
            throw new BizException(401, "用户不存在或已被删除");
        }
        List<String> roles = listRoleCodes(userId);
        LoginVO vo = new LoginVO();
        vo.setUser(toUserVO(user, roles));
        vo.setRoles(roles);
        vo.setMenus(listMenusByRoles(roles));
        return vo;
    }

    @Override
    public List<String> listRoleCodes(Long userId) {
        List<Long> roleIds = userRoleService.list(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleService.listByIds(roleIds).stream().map(SysRole::getRoleCode).toList();
    }

    @Override
    public List<MenuVO> listMenusByRoles(List<String> roles) {
        List<SysMenu> menus;
        if (roles.contains("ADMIN")) {
            // 管理员可见全部菜单
            menus = menuService.list(new LambdaQueryWrapper<SysMenu>()
                    .eq(SysMenu::getStatus, 1).orderByAsc(SysMenu::getSort));
        } else {
            List<Long> roleIds = roleService.list(
                            new LambdaQueryWrapper<SysRole>().in(SysRole::getRoleCode, roles))
                    .stream().map(SysRole::getId).toList();
            if (roleIds.isEmpty()) {
                return List.of();
            }
            List<Long> menuIds = roleMenuService.list(
                            new LambdaQueryWrapper<SysRoleMenu>().in(SysRoleMenu::getRoleId, roleIds))
                    .stream().map(SysRoleMenu::getMenuId).distinct().toList();
            if (menuIds.isEmpty()) {
                return List.of();
            }
            menus = menuService.list(new LambdaQueryWrapper<SysMenu>()
                    .in(SysMenu::getId, menuIds)
                    .eq(SysMenu::getStatus, 1).orderByAsc(SysMenu::getSort));
        }
        return buildTree(menus);
    }

    /** 平铺菜单列表 → 树形结构 */
    private List<MenuVO> buildTree(List<SysMenu> menus) {
        List<MenuVO> vos = menus.stream()
                .map(m -> BeanUtil.copyProperties(m, MenuVO.class))
                .collect(Collectors.toList());
        Map<Long, MenuVO> map = vos.stream()
                .collect(Collectors.toMap(MenuVO::getId, v -> v));
        List<MenuVO> roots = new ArrayList<>();
        for (MenuVO vo : vos) {
            MenuVO parent = map.get(vo.getParentId());
            if (parent == null) {
                roots.add(vo);
            } else {
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(vo);
            }
        }
        return roots;
    }

    private UserVO toUserVO(SysUser user, List<String> roles) {
        UserVO vo = BeanUtil.copyProperties(user, UserVO.class);
        vo.setRoles(roles);
        return vo;
    }
}
