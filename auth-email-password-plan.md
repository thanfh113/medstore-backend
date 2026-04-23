# Auth Email And Password Plan

## Current backend state

- Registration normalizes email with `trim().lowercase()`.
- Profile update now uses the same email normalization and regex validation.
- Email validation accepts any valid mailbox domain; it does not restrict users to `@gmail.com`.
- Internal desktop access is now intended for `ADMIN` and `EMPLOYEE`.
- Shop context should resolve from `shop_staff`, not `shops.owner_id`.

## Recommended auth model

- `USER`: Android/mobile customer account.
- `ADMIN`: desktop internal management account.
- `EMPLOYEE`: desktop internal staff account.
- `SHOP`: deprecated and should not be used for new authz logic.

## Verify email API

### 1. Request verification

- `POST /api/v1/auth/email/verify/request`

Request:

```json
{
  "email": "user@example.com"
}
```

Response:

```json
{
  "message": "Verification email sent if the account exists"
}
```

Rules:

- Always return a neutral response to avoid email enumeration.
- Generate a short-lived token, hashed in DB.
- Suggested expiry: 15-30 minutes.

### 2. Confirm verification

- `POST /api/v1/auth/email/verify/confirm`

Request:

```json
{
  "token": "raw-verification-token"
}
```

Response:

```json
{
  "message": "Email verified successfully"
}
```

Rules:

- Mark user email as verified.
- Revoke the token after first use.

## Forgot password API

### 1. Request reset

- `POST /api/v1/auth/password/forgot`

Request:

```json
{
  "email": "user@example.com"
}
```

Response:

```json
{
  "message": "Password reset email sent if the account exists"
}
```

Rules:

- Neutral response only.
- Generate reset token, store hash in DB.
- Suggested expiry: 15-30 minutes.

### 2. Confirm reset

- `POST /api/v1/auth/password/reset`

Request:

```json
{
  "token": "raw-reset-token",
  "newPassword": "new-strong-password"
}
```

Response:

```json
{
  "message": "Password reset successfully"
}
```

Rules:

- Hash new password with BCrypt.
- Revoke all existing refresh tokens for that user.
- Revoke the reset token after first use.

## Change password API

- `POST /api/v1/auth/change-password`
- Requires authenticated user.

Request:

```json
{
  "currentPassword": "old-password",
  "newPassword": "new-strong-password"
}
```

Response:

```json
{
  "message": "Password changed successfully"
}
```

Rules:

- Verify current password first.
- Reject same-password reuse if desired.
- Revoke all existing refresh tokens after change.

## Suggested DB additions

### `email_verification_tokens`

- `id`
- `user_id`
- `email`
- `token_hash`
- `expires_at`
- `used_at`
- `created_at`

### `password_reset_tokens`

- `id`
- `user_id`
- `email`
- `token_hash`
- `expires_at`
- `used_at`
- `created_at`

## Security notes

- Store only token hashes, never raw tokens.
- Use rate limiting on verify and forgot-password request endpoints.
- Keep email domain validation generic; validate format, not provider.
- Invalidate refresh tokens when password changes or resets.
