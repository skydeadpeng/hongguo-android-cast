import React, { useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import './style.css';
import { nativeCall } from './bridge.mjs';
import { registerAppResolver } from './generated/mac-app-source.mjs';

// Register before rendering so native continuous-play requests can reuse the
// same Mac App-source model resolver while the WebView is in the background.
registerAppResolver(nativeCall);

class AppBoundary extends React.Component {
  state = { error: null };
  static getDerivedStateFromError(error) { return { error }; }
  componentDidCatch() { window.HongguoStartup?.failed('script_error'); }
  render() {
    if (this.state.error) return <main className="fatal-error"><h1>页面遇到了问题</h1><p>{this.state.error.message}</p><button className="button primary" onClick={() => window.location.reload()}>重新打开</button></main>;
    return this.props.children;
  }
}

function CommittedApp() {
  // Native must not treat a loaded HTML document or parsed module as a usable
  // page. This effect runs only after the real App subtree successfully commits.
  useEffect(() => { window.HongguoStartup?.ready(); }, []);
  return <App />;
}

createRoot(document.getElementById('root')).render(<AppBoundary><CommittedApp /></AppBoundary>);
