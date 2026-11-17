import { Navigate, Route, Routes } from "react-router";
import { RouteGuard } from "../auth/RouteGuard";
import { AppLayout } from "../layout/AppLayout";
import { LoginScreen } from "../screens/login/LoginScreen";
import { NotFoundScreen } from "../screens/NotFoundScreen";
import { ProviderCardScreen } from "../screens/providers/ProviderCardScreen";
import { ProviderFormScreen } from "../screens/providers/ProviderFormScreen";
import { ProvidersListScreen } from "../screens/providers/ProvidersListScreen";
import { TariffCardScreen } from "../screens/tariffs/TariffCardScreen";
import { TariffCreateScreen, TariffEditScreen } from "../screens/tariffs/TariffFormScreen";
import { TariffsListScreen } from "../screens/tariffs/TariffsListScreen";

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginScreen />} />
      <Route element={<AppLayout />}>
        <Route path="/" element={<Navigate to="/providers" replace />} />
        <Route path="/providers" element={<ProvidersListScreen />} />
        <Route path="/providers/:id" element={<ProviderCardScreen />} />
        <Route path="/tariffs" element={<TariffsListScreen />} />
        <Route path="/tariffs/:id" element={<TariffCardScreen />} />
        <Route path="/providers/new" element={<RouteGuard><ProviderFormScreen /></RouteGuard>} />
        <Route path="/providers/:id/edit" element={<RouteGuard><ProviderFormScreen /></RouteGuard>} />
        <Route path="/providers/:id/tariffs/new" element={<RouteGuard><TariffCreateScreen /></RouteGuard>} />
        <Route path="/tariffs/:id/edit" element={<RouteGuard><TariffEditScreen /></RouteGuard>} />
      </Route>
      <Route path="*" element={<NotFoundScreen />} />
    </Routes>
  );
}
