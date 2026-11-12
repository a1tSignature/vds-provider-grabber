import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router";
import { SessionProvider } from "./auth/SessionContext";
import { AppRoutes } from "./routes/AppRoutes";
import "./styles/tokens.css";
import "./styles/global.css";

const container = document.getElementById("root");
if (container === null) {
  throw new Error("Missing #root element");
}

createRoot(container).render(
  <BrowserRouter>
    <SessionProvider>
      <AppRoutes />
    </SessionProvider>
  </BrowserRouter>,
);
