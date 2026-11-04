package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.constant.Constant;
import org.apache.commons.mail.HtmlEmail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetDelivery {
    @Value("${security.password-reset.email-enabled:false}") private boolean enabled;
    public void requireAvailable() {
        if (!enabled || Constant.MAIL.isEmpty() || Constant.PASS.isEmpty())
            throw new BusinessRuntimeException(503, "密码找回邮件服务未配置，请联系管理员");
    }
    public void send(String address, String code) {
        requireAvailable();
        try {
            HtmlEmail mail = new HtmlEmail();
            mail.setHostName(Constant.MAIL_HOST);
            mail.setSmtpPort(587);
            mail.setStartTLSEnabled(true);
            mail.setStartTLSRequired(true);
            mail.setSocketConnectionTimeout(3000);
            mail.setSocketTimeout(5000);
            mail.setAuthentication(Constant.MAIL, Constant.PASS);
            mail.setFrom(Constant.MAIL);
            mail.addTo(address);
            mail.setCharset("UTF-8");
            mail.setSubject("密码找回验证码");
            mail.setMsg("验证码：" + code + "，5分钟内有效，仅可使用一次。非本人操作请忽略。");
            mail.send();
        } catch (Exception e) {
            throw new BusinessRuntimeException(503, "验证码发送失败，请稍后重试");
        }
    }
}
