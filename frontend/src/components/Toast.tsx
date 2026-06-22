import { useEffect } from "react";

interface ToastProps {
  message: string;
  query: string;
  onDismiss: () => void;
}

export function Toast({ message, query, onDismiss }: ToastProps) {
  useEffect(() => {
    const id = window.setTimeout(onDismiss, 2600);
    return () => window.clearTimeout(id);
  }, [message, query, onDismiss]);

  return (
    <div
      role="status"
      className="pointer-events-none fixed inset-x-0 bottom-8 z-30 flex justify-center px-4"
    >
      <div className="pointer-events-auto flex items-center gap-3 rounded-full bg-ink px-5 py-3 text-sm text-white shadow-pop animate-slide-up">
        <span className="flex h-5 w-5 items-center justify-center rounded-full bg-accent text-[11px] font-bold">
          ✓
        </span>
        <span className="font-medium">{message}</span>
        <span className="max-w-[16rem] truncate text-white/60">“{query}”</span>
      </div>
    </div>
  );
}
