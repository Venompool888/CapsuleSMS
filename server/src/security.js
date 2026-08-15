import {
  createHash,
  randomBytes,
  scrypt as scryptCallback,
  timingSafeEqual,
} from "node:crypto";
import { promisify } from "node:util";

const scrypt = promisify(scryptCallback);
const SCRYPT_OPTIONS = { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 };

export function randomToken(bytes = 32) {
  return randomBytes(bytes).toString("base64url");
}

export function tokenHash(token) {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

export async function hashPassword(password) {
  validatePassword(password);
  const salt = randomBytes(16);
  const derived = await scrypt(password, salt, 32, SCRYPT_OPTIONS);
  return [
    "scrypt",
    SCRYPT_OPTIONS.N,
    SCRYPT_OPTIONS.r,
    SCRYPT_OPTIONS.p,
    salt.toString("base64url"),
    Buffer.from(derived).toString("base64url"),
  ].join("$");
}

export async function verifyPassword(password, encoded) {
  try {
    const [kind, n, r, p, saltText, expectedText] = encoded.split("$");
    if (kind !== "scrypt") return false;
    const expected = Buffer.from(expectedText, "base64url");
    const actual = Buffer.from(await scrypt(
      password,
      Buffer.from(saltText, "base64url"),
      expected.length,
      { N: Number(n), r: Number(r), p: Number(p), maxmem: 64 * 1024 * 1024 },
    ));
    return actual.length === expected.length && timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

export function validatePassword(password) {
  if (typeof password !== "string" || password.length < 12 || password.length > 256) {
    throw new Error("密码长度必须为 12–256 个字符");
  }
}

export function normalizeUsername(value) {
  const username = String(value ?? "").trim().toLowerCase();
  if (!/^[a-z0-9][a-z0-9._-]{2,31}$/.test(username)) {
    throw new Error("用户名需为 3–32 位小写字母、数字、点、下划线或短横线");
  }
  return username;
}

export function safeEqualText(left, right) {
  const a = Buffer.from(String(left ?? ""));
  const b = Buffer.from(String(right ?? ""));
  return a.length === b.length && timingSafeEqual(a, b);
}
