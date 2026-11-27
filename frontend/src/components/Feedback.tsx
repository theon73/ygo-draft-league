import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";

// App-wide feedback (YGO-28): a confirm dialog that replaces window.confirm, and toasts for
// "Saved."-style messages. Both live in one provider near the root, and pages reach them through
// hooks: `const confirm = useConfirm(); if (!(await confirm({...}))) return;` and `toast("Saved.")`.
//
// The dialog is the browser's native <dialog> opened with showModal(): it traps focus, closes on
// Escape and puts everything behind it out of reach, without a library.

export interface ConfirmOptions {
  title: string;
  message?: ReactNode;
  confirmLabel?: string; // default "Confirm"
  cancelLabel?: string; // default "Cancel"
  danger?: boolean; // destructive: the confirm button is red
  media?: ReactNode; // shown beside the text, e.g. the card being drafted (YGO-29)
}

type ToastKind = "good" | "bad";
interface Toast { id: number; text: string; kind: ToastKind }

interface FeedbackContext {
  confirm: (options: ConfirmOptions) => Promise<boolean>;
  toast: (text: string, kind?: ToastKind) => void;
}

const Ctx = createContext<FeedbackContext | null>(null);

function useFeedback(): FeedbackContext {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("FeedbackProvider is missing");
  return ctx;
}

export const useConfirm = () => useFeedback().confirm;
export const useToast = () => useFeedback().toast;

export function FeedbackProvider({ children }: { children: ReactNode }) {
  const [request, setRequest] = useState<(ConfirmOptions & { resolve: (ok: boolean) => void }) | null>(null);
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(0);

  const confirm = useCallback((options: ConfirmOptions) => new Promise<boolean>((resolve) => {
    setRequest({ ...options, resolve });
  }), []);

  const toast = useCallback((text: string, kind: ToastKind = "good") => {
    const id = ++nextId.current;
    setToasts((t) => [...t, { id, text, kind }]);
    window.setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === "bad" ? 6000 : 3500);
  }, []);

  const close = (ok: boolean) => {
    request?.resolve(ok);
    setRequest(null);
  };

  return (
    <Ctx.Provider value={{ confirm, toast }}>
      {children}
      {request && <ConfirmDialog options={request} onClose={close} />}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map((t) => <div key={t.id} className={`toast ${t.kind}`}>{t.text}</div>)}
      </div>
    </Ctx.Provider>
  );
}

function ConfirmDialog({ options, onClose }: { options: ConfirmOptions; onClose: (ok: boolean) => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);
  return (
    // onCancel fires on Escape; a click on the backdrop lands on the dialog element itself.
    <dialog ref={ref} className="confirm" onCancel={(e) => { e.preventDefault(); onClose(false); }}
            onClick={(e) => { if (e.target === ref.current) onClose(false); }} aria-labelledby="confirm-title">
      {/* The padding lives on this wrapper, so a click inside the box never counts as the backdrop. */}
      <div className={`dialog-body${options.media ? " with-media" : ""}`}>
        {options.media && <div className="dialog-media">{options.media}</div>}
        <div className="dialog-text">
          <h4 id="confirm-title">{options.title}</h4>
          {options.message && <div className="dialog-message">{options.message}</div>}
        </div>
        <div className="dialog-actions">
          <button className="ghost" onClick={() => onClose(false)}>{options.cancelLabel ?? "Cancel"}</button>
          <button className={options.danger ? "danger" : "primary"} onClick={() => onClose(true)} autoFocus>
            {options.confirmLabel ?? "Confirm"}
          </button>
        </div>
      </div>
    </dialog>
  );
}

export function Loading({ what }: { what?: string }) {
  return <div className="loading"><span className="spinner" aria-hidden="true" />Loading{what ? ` ${what}` : ""}…</div>;
}

/** A friendly placeholder for "nothing here yet", with an optional next step. */
export function EmptyState({ title, children, actions }: { title: string; children?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="empty">
      <h4>{title}</h4>
      {children}
      {actions && <div className="empty-actions">{actions}</div>}
    </div>
  );
}
