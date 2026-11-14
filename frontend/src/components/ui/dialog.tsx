"use client";

import * as DialogPrimitive from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import { Button } from "./button";

export function ConfirmDialog({
  title,
  description,
  trigger,
  confirm,
  busy = false,
}: {
  title: string;
  description: string;
  trigger: string;
  confirm: () => Promise<void>;
  busy?: boolean;
}) {
  return (
    <DialogPrimitive.Root>
      <DialogPrimitive.Trigger asChild>
        <Button variant="outline" disabled={busy}>
          {trigger}
        </Button>
      </DialogPrimitive.Trigger>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="dialog-overlay" />
        <DialogPrimitive.Content className="dialog-content">
          <DialogPrimitive.Title>{title}</DialogPrimitive.Title>
          <DialogPrimitive.Description>
            {description}
          </DialogPrimitive.Description>
          <div className="actions">
            <DialogPrimitive.Close asChild>
              <Button variant="outline">Cancel</Button>
            </DialogPrimitive.Close>
            <DialogPrimitive.Close asChild>
              <Button variant="destructive" onClick={() => void confirm()}>
                Confirm report
              </Button>
            </DialogPrimitive.Close>
          </div>
          <DialogPrimitive.Close asChild>
            <Button
              variant="ghost"
              className="dialog-close"
              aria-label="Close dialog"
            >
              <X size={18} />
            </Button>
          </DialogPrimitive.Close>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
