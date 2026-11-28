// A server-sent-event connection that keeps itself alive (YGO-35). EventSource (the browser's SSE
// client) reconnects by itself after a cleanly dropped connection, but not in two cases that
// otherwise leave a page deaf to live updates until it's reloaded:
//  - a reconnect gets an error response (e.g. a proxy's 502 while the backend restarts or deploys):
//    the browser gives up for good;
//  - a proxy keeps the browser's side of the stream open after the backend went away (Vite's dev
//    proxy does on a backend restart): the page thinks it's connected and hears nothing.
// So: the server sends a "ping" event every 15s; if nothing arrives for 35s, or the browser gave
// up, reconnect (backing off from 1s to 30s while it keeps failing). The server answers every
// (re)connect with a current-state event, so listeners catch up on what they missed.
// Returns a function that closes it.
const SILENCE_MS = 35_000;

export function openEventStream(url: string, listeners: Record<string, (e: MessageEvent<string>) => void>): () => void {
  let source: EventSource | null = null;
  let stopped = false;
  let retry: number | undefined;
  let delay = 1000;
  let lastHeard = Date.now();

  const connect = () => {
    source?.close();
    lastHeard = Date.now();
    const s = new EventSource(url);
    source = s;
    const heard = () => { lastHeard = Date.now(); };
    s.onopen = () => { heard(); delay = 1000; };
    s.addEventListener("ping", heard);
    for (const [name, listener] of Object.entries(listeners)) {
      s.addEventListener(name, ((e: MessageEvent<string>) => { heard(); listener(e); }) as EventListener);
    }
    s.onerror = () => {
      if (stopped || s.readyState !== EventSource.CLOSED) return; // CONNECTING: the browser is retrying itself
      reconnectSoon();
    };
  };
  const reconnectSoon = () => {
    source?.close();
    window.clearTimeout(retry);
    retry = window.setTimeout(connect, delay);
    delay = Math.min(delay * 2, 30_000);
  };
  // A connection that's gone quiet is presumed dead.
  const watchdog = window.setInterval(() => {
    if (!stopped && Date.now() - lastHeard > SILENCE_MS) { lastHeard = Date.now(); reconnectSoon(); }
  }, 5_000);
  connect();

  return () => {
    stopped = true;
    window.clearTimeout(retry);
    window.clearInterval(watchdog);
    source?.close();
  };
}
