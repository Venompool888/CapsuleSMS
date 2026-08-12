package io.github.venompool888.capsulesms.sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class OtpParserTest {
    @Test public void parsesChineseOtpAndValidity() {
        OtpParser.Result result = OtpParser.parse("您的验证码为 482913，5 分钟内有效");
        assertEquals(OtpParser.Result.Status.SUCCESS, result.status());
        assertEquals("482913", result.code());
        assertEquals(Integer.valueOf(5), result.validityMinutes());
    }

    @Test public void parsesCodeFromCurrentMessage() {
        OtpParser.Result result = OtpParser.parse(
                "【清枫北京科技】您的验证码：7431，如非本人操作，请忽略本短信！");
        assertEquals(OtpParser.Result.Status.SUCCESS, result.status());
        assertEquals("7431", result.code());
    }

    @Test public void parsesCodeBeforeKeyword() {
        assertEquals("482913", OtpParser.parse(
                "482913 是您的登录验证码，请勿向他人泄露").code());
    }

    @Test public void parsesEnglishAndMixedCodes() {
        assertEquals("3812", OtpParser.parse("Your verification code is 3812").code());
        assertEquals("A7K29Q", OtpParser.parse(
                "Your verification code is A7K29Q. Do not share this code.").code());
    }

    @Test public void rejectsAmountWithoutOtpMeaning() {
        OtpParser.Result result = OtpParser.parse("您尾号 1234 的账户支出 88.50 元");
        assertEquals(OtpParser.Result.Status.NONE, result.status());
        assertNull(result.code());
    }

    @Test public void selectsCodeNearestKeyword() {
        assertEquals("905771", OtpParser.parse(
                "尾号 1234 的账户验证码为 905771，请勿告诉他人").code());
    }

    @Test public void rejectsOrderAndCarrierMessages() {
        assertEquals(OtpParser.Result.Status.NONE,
                OtpParser.parse("订单 20260809123456 已发货").status());
        assertEquals(OtpParser.Result.Status.NONE, OtpParser.parse(
                "【订购提醒】您已成功开通套餐，编号 25JS205838，2026-08-09 生效").status());
    }

    @Test public void rejectsServiceNumberNearSafetyReminder() {
        assertEquals(OtpParser.Result.Status.NONE, OtpParser.parse(
                "请勿向任何人提供验证码，如有疑问请拨打客服热线 10086").status());
    }

    @Test public void rejectsLongPhoneNumberEvenNearKeyword() {
        assertEquals(OtpParser.Result.Status.NONE, OtpParser.parse(
                "验证码有问题请联系客服 15995625775").status());
    }

    @Test public void rejectsAmbiguousCandidates() {
        OtpParser.Result result = OtpParser.parse("验证码 123456 或 654321，请勿泄露");
        assertEquals(OtpParser.Result.Status.AMBIGUOUS, result.status());
        assertNull(result.code());
    }

    @Test public void rejectsOtpWarningWithNoCode() {
        assertEquals(OtpParser.Result.Status.NONE, OtpParser.parse(
                "警方提醒：不要向陌生人泄露验证码或开启屏幕共享").status());
    }
}
