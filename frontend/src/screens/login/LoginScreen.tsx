import { useState, type FormEvent } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { useApiMutation } from "../../api/hooks";
import { clientErrorMessage, type ApiClientError } from "../../api/client";
import type { LoginResponse } from "../../api/types";
import { sanitizeNext } from "../../auth/nextPath";
import { useSession } from "../../auth/SessionContext";

interface LoginRequest {
  username: string;
  password: string;
}

export function LoginScreen() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { login } = useSession();
  const { mutate, error, loading } = useApiMutation<LoginRequest, LoginResponse>(
    "POST",
    "/api/v1/auth/login",
    { skipAuth: true },
  );

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    try {
      const result = await mutate({ username, password });
      login(result);
      navigate(sanitizeNext(searchParams.get("next")), { replace: true });
    } catch {
    }
  }

  const notice = searchParams.get("notice") === "expired" ? "сессия истекла, войдите снова" : null;
  const fieldErrors = error?.kind === "validation" ? error.details : [];
  const fieldError = (field: string) => fieldErrors.find((detail) => detail.field === field)?.message;

  return (
    <div className="login-screen">
      <form onSubmit={handleSubmit}>
        <h1>VDS Catalog</h1>
        {notice !== null && <p className="login-notice">{notice}</p>}
        {error !== undefined && error.kind !== "validation" && <p className="login-error">{loginErrorMessage(error)}</p>}
        <label>
          Логин
          <input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" />
          {fieldError("username") !== undefined && <span className="field-error">{fieldError("username")}</span>}
        </label>
        <label>
          Пароль
          <input
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
          />
          {fieldError("password") !== undefined && <span className="field-error">{fieldError("password")}</span>}
        </label>
        <button type="submit" className="btn-primary" disabled={loading}>
          Войти
        </button>
      </form>
    </div>
  );
}

function loginErrorMessage(error: ApiClientError): string {
  switch (error.kind) {
    case "unauthorized":
      return "неверный логин или пароль";
    case "rateLimited":
      return error.retryAfterSeconds !== undefined
        ? `слишком много попыток, подождите ${error.retryAfterSeconds} с`
        : "слишком много попыток";
    case "server":
      return error.status === 503 ? "сервис занят, попробуйте ещё раз" : error.message || "ошибка сервера";
    case "malformed":
      return "некорректный запрос";
    case "network":
      return "нет соединения с сервером";
    default:
      return clientErrorMessage(error) || "ошибка";
  }
}
