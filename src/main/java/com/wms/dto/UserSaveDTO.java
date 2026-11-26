package com.wms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员新增/编辑用户请求
 */
@Data
public class UserSaveDTO {

    /** 编辑时必传，新增为 null */
    private Long id;

    @NotBlank(message = "账号不能为空")
    @Size(min = 4, max = 20, message = "账号长度需为 4~20 位")
    private String username;

    @NotBlank(message = "姓名不能为空")
    private String realName;

    private String nickname;

    private String dept;

    private String phone;

    @NotNull(message = "角色不能为空")
    private Long roleId;

    /** 新增时的初始密码，为空默认 123456 */
    private String password;
}
