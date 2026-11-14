"use client";

import { Button } from "@/components/ui/button";
export default function ErrorPage({ reset }: { reset: () => void }) {
  return (
    <div className="container section">
      <h1>Something interrupted your visit.</h1>
      <p role="alert">OpenNest could not load this page. Please try again.</p>
      <Button onClick={reset}>Try again</Button>
    </div>
  );
}
