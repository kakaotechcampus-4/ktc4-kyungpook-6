import { useUnauthorizedRedirect } from "../hooks/unauthorizedRedirect";

/** 401 이면 로그인 화면으로 보내는 핸들러를 붙인다. 화면에는 아무것도 그리지 않는다. BrowserRouter 안에 둔다. */
function UnauthorizedRedirect() {
  useUnauthorizedRedirect();
  return null;
}

export default UnauthorizedRedirect;
