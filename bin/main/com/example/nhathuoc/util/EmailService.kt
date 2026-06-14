package com.example.nhathuoc.util

import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object EmailService {
    private val gmailUser: String get() = Env.get("GMAIL_USER") ?: ""
    private val gmailPassword: String get() = Env.get("GMAIL_APP_PASSWORD") ?: ""

    fun sendPasswordResetOtp(toEmail: String, otp: String) {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(gmailUser, gmailPassword)
        })

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(gmailUser, "MedStore", "UTF-8"))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
            subject = "Mã xác nhận đặt lại mật khẩu - MedStore"
            setContent(buildEmailHtml(otp), "text/html; charset=UTF-8")
        }

        Transport.send(message)
    }

    private fun buildEmailHtml(otp: String) = """
        <!DOCTYPE html>
        <html>
        <body style="font-family: Arial, sans-serif; background: #f5f5f5; margin: 0; padding: 20px;">
            <div style="max-width: 480px; margin: 0 auto; background: white; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
                <div style="background: linear-gradient(135deg, #2E7D32, #66BB6A); padding: 28px; text-align: center;">
                    <h2 style="color: white; margin: 0; font-size: 24px; letter-spacing: 1px;">MedStore</h2>
                    <p style="color: rgba(255,255,255,0.85); margin: 6px 0 0; font-size: 13px;">Vật tư y tế chất lượng cao</p>
                </div>
                <div style="padding: 32px 28px;">
                    <h3 style="color: #1a1a1a; margin: 0 0 12px;">Đặt lại mật khẩu</h3>
                    <p style="color: #555; margin: 0 0 20px; line-height: 1.6;">
                        Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn. Nhập mã OTP dưới đây trong ứng dụng:
                    </p>
                    <div style="background: #e8f5e9; border: 2px solid #c8e6c9; border-radius: 10px; padding: 20px; text-align: center; margin: 0 0 20px;">
                        <span style="font-size: 40px; font-weight: bold; color: #2E7D32; letter-spacing: 12px;">$otp</span>
                    </div>
                    <p style="color: #555; margin: 0 0 8px; font-size: 14px;">
                        ⏱ Mã có hiệu lực trong <strong>10 phút</strong>.
                    </p>
                    <p style="color: #888; font-size: 12px; margin: 0; border-top: 1px solid #eee; padding-top: 16px;">
                        Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này. Tài khoản của bạn vẫn an toàn.
                    </p>
                </div>
            </div>
        </body>
        </html>
    """.trimIndent()
}
