import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import AreaMap from "./area-map";

vi.mock("./session", () => ({
  useSession: () => ({ config: { mapKey: "" } }),
}));
it("keeps a usable text alternative when no provider key is configured", () => {
  render(<AreaMap />);
  expect(screen.getByRole("status")).toHaveTextContent(
    "Browse the listings and locality information below.",
  );
  expect(screen.queryByRole("region")).not.toBeInTheDocument();
});
