package io.github.venompool888.capsulesms.sms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative, entirely local OTP parser. */
public final class OtpParser {
    private static final Pattern KEYWORD = Pattern.compile(
            "验证码|验证密码|校验码|动态(?:验证)?码|动态密码|一次性密码|短信码|交易码|"
                    + "otp|verification\\s*code|verify\\s*code|passcode|security\\s*code|"
                    + "login\\s*code|authentication\\s*code|confirmation\\s*code|"
                    + "one[-\\s]*time\\s*(?:password|code)|your\\s+(?:login\\s+)?code|code\\s+is",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CANDIDATE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([A-Za-z0-9]{4,10})(?![\\p{L}\\p{N}])");
    private static final Pattern VALIDITY = Pattern.compile(
            "([0-9]{1,2})\\s*(?:分钟|min(?:ute)?s?)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MONEY_CONTEXT = Pattern.compile(
            "(?:¥|￥|元|金额|支付|支出|收入|余额|账单|price|amount|paid|payment)\\s*[0-9,.]*$|"
                    + "^[0-9,.]*\\s*(?:元|rmb|cny|aud|usd)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TIME_OR_DATE = Pattern.compile(
            "(?:^|\\D)(?:20[0-9]{2}[-/.])?[01]?[0-9][-/.:][0-3]?[0-9](?:\\D|$)");
    private static final Pattern CONTACT_CONTEXT = Pattern.compile(
            "拨打|致电|联系客服|客服(?:电话|热线)?|热线|发送至|回复至|call|contact|hotline|customer\\s*service",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern REFERENCE_CONTEXT = Pattern.compile(
            "订单|订购|套餐|编号|流水号|参考号|快递|物流|运单|航班|预约|"
                    + "order|reference|tracking|booking|flight|subscription|plan",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SECRECY = Pattern.compile(
            "请勿(?:泄露|告知|分享|转发)|不要(?:泄露|分享|告诉)|切勿(?:泄露|告知)|"
                    + "do\\s*not\\s*share|don't\\s*share|never\\s*share|keep\\s*(?:it\\s*)?secret",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern AUTH_CONTEXT = Pattern.compile(
            "登录|登入|注册|重置|认证|验证身份|login|log\\s*in|sign\\s*in|register|reset|authenticate|confirm",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern URL = Pattern.compile(
            "(?:https?://|www\\.)\\S+", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private OtpParser() {}

    public static Result parse(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) return Result.none();

        List<Range> keywords = ranges(KEYWORD, normalized);
        if (keywords.isEmpty()) return Result.none();
        List<Range> dates = ranges(TIME_OR_DATE, normalized);
        List<Range> urls = ranges(URL, normalized);
        Map<String, Candidate> unique = new LinkedHashMap<>();
        Matcher matcher = CANDIDATE.matcher(normalized);
        while (matcher.find()) {
            String code = matcher.group(1);
            boolean allDigits = code.chars().allMatch(Character::isDigit);
            boolean hasDigit = code.chars().anyMatch(Character::isDigit);
            boolean hasLetter = code.chars().anyMatch(Character::isLetter);
            if ((allDigits && code.length() > 8) || (!allDigits && !(hasDigit && hasLetter))) {
                continue;
            }

            Range codeRange = new Range(matcher.start(1), matcher.end(1) - 1);
            Range closestKeyword = keywords.stream()
                    .min(Comparator.comparingInt(keyword -> distance(codeRange, keyword)))
                    .orElseThrow();
            int keywordDistance = distance(codeRange, closestKeyword);
            boolean followsKeyword = codeRange.start > closestKeyword.end;
            String context = slice(normalized, codeRange.start - 24, codeRange.end + 25);
            String localContext = slice(normalized, codeRange.start - 10, codeRange.end + 11);
            int score = 25 + proximityScore(followsKeyword, keywordDistance);
            score += code.length() == 6 ? 15 : (code.length() >= 4 && code.length() <= 8 ? 8 : 5);
            if (hasDigit && hasLetter) score += 5;
            if (VALIDITY.matcher(context).find()) score += 5;
            if (SECRECY.matcher(context).find()) score += 8;
            if (AUTH_CONTEXT.matcher(context).find()) score += 5;
            if (MONEY_CONTEXT.matcher(localContext).find()) score -= 70;
            if (overlapsAny(codeRange, dates)) score -= 50;
            if (CONTACT_CONTEXT.matcher(context).find()) score -= 90;
            if (REFERENCE_CONTEXT.matcher(context).find()) score -= 60;
            if (overlapsAny(codeRange, urls)) score -= 100;
            if (allSame(code)) score -= 20;
            Candidate candidate = new Candidate(code, clamp(score));
            Candidate previous = unique.get(code.toUpperCase(Locale.ROOT));
            if (previous == null || candidate.score > previous.score) {
                unique.put(code.toUpperCase(Locale.ROOT), candidate);
            }
        }

        List<Candidate> candidates = new ArrayList<>(unique.values());
        candidates.sort(Comparator.comparingInt((Candidate value) -> value.score).reversed());
        if (candidates.isEmpty() || candidates.get(0).score < 75) return Result.none();
        Candidate best = candidates.get(0);
        if (candidates.size() > 1) {
            Candidate second = candidates.get(1);
            if (second.score >= 75 && best.score - second.score < 15) {
                int count = (int) candidates.stream().filter(value -> value.score >= 75).count();
                return Result.ambiguous(count);
            }
        }
        Integer validityMinutes = null;
        Matcher validity = VALIDITY.matcher(normalized);
        if (validity.find()) validityMinutes = Integer.parseInt(validity.group(1));
        return Result.success(best.code, best.score, validityMinutes);
    }

    private static int proximityScore(boolean follows, int distance) {
        if (follows) {
            if (distance <= 4) return 65;
            if (distance <= 12) return 55;
            if (distance <= 24) return 40;
        } else {
            if (distance <= 4) return 55;
            if (distance <= 12) return 45;
            if (distance <= 24) return 25;
        }
        return 0;
    }

    private static List<Range> ranges(Pattern pattern, String text) {
        List<Range> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) result.add(new Range(matcher.start(), matcher.end() - 1));
        return result;
    }

    private static int distance(Range left, Range right) {
        if (left.end < right.start) return right.start - left.end;
        if (right.end < left.start) return left.start - right.end;
        return 0;
    }

    private static boolean overlapsAny(Range target, List<Range> ranges) {
        for (Range range : ranges) {
            if (target.start <= range.end && range.start <= target.end) return true;
        }
        return false;
    }

    private static String slice(String value, int start, int endExclusive) {
        return value.substring(Math.max(0, start), Math.min(value.length(), endExclusive));
    }

    private static boolean allSame(String value) {
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) != value.charAt(0)) return false;
        }
        return true;
    }

    private static int clamp(int value) { return Math.max(0, Math.min(100, value)); }

    private static final class Range {
        final int start;
        final int end;
        Range(int start, int end) { this.start = start; this.end = end; }
    }

    private static final class Candidate {
        final String code;
        final int score;
        Candidate(String code, int score) { this.code = code; this.score = score; }
    }

    public static final class Result {
        public enum Status { SUCCESS, AMBIGUOUS, NONE }

        private final Status status;
        private final String code;
        private final int confidence;
        private final Integer validityMinutes;
        private final int candidateCount;

        private Result(Status status, String code, int confidence,
                Integer validityMinutes, int candidateCount) {
            this.status = status;
            this.code = code;
            this.confidence = confidence;
            this.validityMinutes = validityMinutes;
            this.candidateCount = candidateCount;
        }

        static Result success(String code, int confidence, Integer validityMinutes) {
            return new Result(Status.SUCCESS, code, confidence, validityMinutes, 1);
        }

        static Result ambiguous(int candidateCount) {
            return new Result(Status.AMBIGUOUS, null, 0, null, candidateCount);
        }

        static Result none() { return new Result(Status.NONE, null, 0, null, 0); }

        public Status status() { return status; }
        public String code() { return code; }
        public int confidence() { return confidence; }
        public Integer validityMinutes() { return validityMinutes; }
        public int candidateCount() { return candidateCount; }
    }
}
