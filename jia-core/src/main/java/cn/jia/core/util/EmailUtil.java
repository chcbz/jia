package cn.jia.core.util;

import lombok.extern.slf4j.Slf4j;

import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.util.Properties;

/**
 * @author chc
 */
@Slf4j
public class EmailUtil {
	/**
	 * MIME邮件对象
	 */
	private MimeMessage mimeMsg;
	/**
	 * 邮件会话对象
	 */
	private Session session;
	/**
	 * 系统属性
	 */
	private Properties props;
	/*
	 * smtp是否需要认证
	 */
	// private boolean needAuth = false;
	/**
	 * smtp认证用户名和密码
	 */
	private String username = "";
	private String password = "";
	/**
	 * Multipart对象,邮件内容,标题,附件等内容均添加到其中后再生成MimeMessage对象
	 */
	private Multipart mp;

	public EmailUtil() {
		// 如果没有指定邮件服务器,就从getConfig类中获取
		// setSmtpHost(getConfig.mailHost);
		// 如果没有指定邮件服务器,就从getConfig类中获取
		setSmtpHost("smtp.126.com");
		createMimeMessage();
	}

	public EmailUtil(String smtp) {
		setSmtpHost(smtp);
		createMimeMessage();
	}

	/**
	 * 设置系统属性：mail.smtp.host
	 * @param hostName SMTP主机名
	 */
	public void setSmtpHost(String hostName) {
		log.info("设置系统属性：mail.smtp.host = " + hostName);
		if (props == null) {
			// 获得系统属性对象
			props = System.getProperties();
		}
		// 设置SMTP主机
		props.put("mail.smtp.host", hostName);
	}

	/**
	 * 准备获取邮件会话对象
	 * @return boolean
	 */
	public boolean createMimeMessage() {
		try {
			log.info("准备获取邮件会话对象！");
			// 获得邮件会话对象
			session = Session.getDefaultInstance(props, null);
		} catch (Exception e) {
			log.error("获取邮件会话对象时发生错误！" + e);
			return false;
		}
		log.info("准备创建MIME邮件对象！");
		try {
			// 创建MIME邮件对象
			mimeMsg = new MimeMessage(session);
			mp = new MimeMultipart();
			return true;
		} catch (Exception e) {
			log.error("创建MIME邮件对象失败！", e);
			return false;
		}
	}

	/**
	 * 设置smtp身份认证：mail.smtp.auth
	 * @param need 是否需要身份认证
	 */
	public void setNeedAuth(boolean need) {
		log.info("设置smtp身份认证：mail.smtp.auth = " + need);
		if (props == null) {
			props = System.getProperties();
		}
		if (need) {
			props.put("mail.smtp.auth", "true");
		} else {
			props.put("mail.smtp.auth", "false");
		}
	}

	/**
	 * 设置用户名密码
	 * @param name 用户名
	 * @param pass 密码
	 */
	public void setNamePass(String name, String pass) {
		username = name;
		password = pass;
	}

	/**
	 * 设置邮件主题
	 * @param mailSubject 主题
	 * @return boolean
	 */
	public boolean setSubject(String mailSubject) {
		log.info("设置邮件主题！");
		try {
			mimeMsg.setSubject(mailSubject);
			return true;
		} catch (Exception e) {
			log.error("设置邮件主题发生错误！", e);
			return false;
		}
	}

	/**
	 * 设置邮件正文
	 * @param mailBody 正文
	 */
	public boolean setBody(String mailBody) {
		try {
			BodyPart bp = new MimeBodyPart();
			bp.setContent("<meta http-equiv=Content-Type content=text/html; charset=gb2312>" + mailBody,
					"text/html;charset=GB2312");
			mp.addBodyPart(bp);
			return true;
		} catch (Exception e) {
			log.error("设置邮件正文时发生错误！", e);
			return false;
		}
	}

	/**
	 * 增加邮件附件
	 * @param filename 附件名
	 */
	public boolean addFileAffix(String filename) {
		log.info("增加邮件附件：" + filename);
		try {
			BodyPart bp = new MimeBodyPart();
			FileDataSource fileds = new FileDataSource(filename);
			bp.setDataHandler(new DataHandler(fileds));
			bp.setFileName(fileds.getName());
			mp.addBodyPart(bp);
			return true;
		} catch (Exception e) {
			log.error("增加邮件附件：" + filename + "发生错误！", e);
			return false;
		}
	}

	/**
	 * 设置发件人
	 * @param from 发件人
	 */
	public boolean setFrom(String from) {
		log.info("设置发信人！");
		try {
			// 设置发信人
			mimeMsg.setFrom(new InternetAddress(from));
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * 设置收件人
	 * @param to 收件人
	 */
	public boolean setTo(String to) {
		if (to == null) {
			return false;
		}
		try {
			mimeMsg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * 设置抄送人
	 * @param copyto 抄送人
	 */
	public boolean setCopyTo(String copyto) {
		if (copyto == null) {
			return false;
		}
		try {
			mimeMsg.setRecipients(Message.RecipientType.CC, InternetAddress.parse(copyto));
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * 发送邮件
	 */
	public boolean sendout() {
		try {
			mimeMsg.setContent(mp);
			mimeMsg.saveChanges();
			log.info("正在发送邮件....");
			Session mailSession = Session.getInstance(props, null);
			Transport transport = mailSession.getTransport("smtp");
			transport.connect((String) props.get("mail.smtp.host"), username, password);
			transport.sendMessage(mimeMsg, mimeMsg.getRecipients(Message.RecipientType.TO));
			// transport.send(mimeMsg);
			log.info("发送邮件成功！");
			transport.close();
			return true;
		} catch (Exception e) {
			log.error("邮件发送失败！", e);
			return false;
		}
	}

	/**
	 * 发送邮件的静态方法
	 * @param title 邮件标题
	 * @param content 邮件主体内容
	 * @param from 邮件的发送方
	 * @param to 邮件的接收方
	 * @param name 发送方邮箱的用户名
	 * @param password 发送方邮箱的密码
	 * @param smtp 发送方邮箱的SMTP服务器地址,如smtp.qq.com
	 * @return 是否成功
	 */
	public static boolean doSend(String title, String content, String from, String to, String name, String password,
			String smtp) {
		String mailbody = "<meta http-equiv=Content-Type content=text/html; charset=gb2312>" + content;
		EmailUtil themail = new EmailUtil(smtp);
		themail.setNeedAuth(true);
		if (!themail.setSubject(title)) {
			return false;
		}
		if (!themail.setBody(mailbody)) {
			return false;
		}
		if (!themail.setTo(to)) {
			return false;
		}
		if (!themail.setFrom(from)) {
			return false;
		}
		themail.setNamePass(name, password);
		return themail.sendout();
	}

	/**
	 * 发送邮件的静态方法,可以发送附件
	 * @param title 邮件标题
	 * @param content 邮件主体内容
	 * @param from 邮件的发送方
	 * @param to 邮件的接收方
	 * @param affixPath 附件的绝对地址
	 * @param name 发送方邮箱的用户名
	 * @param password 发送方邮箱的密码
	 * @param smtp 发送方邮箱的SMTP服务器地址,如smtp.qq.com
	 * @return 是否成功发送
	 */
	public static boolean doSend(String title, String content, String from, String to, String affixPath, String name,
			String password, String smtp) {
		String mailbody = "<meta http-equiv=Content-Type content=text/html; charset=gb2312>" + content;
		EmailUtil themail = new EmailUtil(smtp);
		themail.setNeedAuth(true);
		if (!themail.setSubject(title)) {
			return false;
		}
		if (!themail.setBody(mailbody)) {
			return false;
		}
		if (!themail.setTo(to)) {
			return false;
		}
		if (!themail.setFrom(from)) {
			return false;
		}
		if (!themail.addFileAffix(affixPath)) {
			return false;
		}
		themail.setNamePass(name, password);
		return themail.sendout();
	}

	public static void main(String[] args) {
		log.info("{}", EmailUtil.doSend("test", "content", "12349@e2tt.com", "chcbz@qq.com", "12349@e2tt.com", "ngguknecfrpyeihd", "smtp.qq.com"));
	}
}