package cn.jia.user.entity;

import java.io.Serializable;
import java.util.Date;

import cn.afterturn.easypoi.excel.annotation.Excel;
import lombok.Data;

@Data
public class UserImport implements Serializable {
	private static final long serialVersionUID = -1293383890141322769L;

	@Excel(name = "用户名", orderNum = "0")
    private String username;

	@Excel(name = "密码", orderNum = "1")
    private String password;

	@Excel(name = "手机号码", orderNum = "2")
    private String phone;

	@Excel(name = "邮箱", orderNum = "3")
    private String email;

	@Excel(name = "性别", replace = {"男_1", "女_2"}, orderNum = "4")
    private Integer sex;

	@Excel(name = "真实姓名", orderNum = "5")
    private String nickname;

	@Excel(name = "生日", exportFormat = "yyyy-MM-dd", orderNum = "6")
    private Date birthday;
}