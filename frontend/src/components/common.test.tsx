import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { imageUrl, Pagination, TrustBadge } from "./common";

describe("accessible listing navigation", () => {
  it("keeps direct storage URLs usable when browser API calls use the same-origin development proxy", () => {
    expect(imageUrl("https://storage.example.invalid/photo.webp", "")).toBe("https://storage.example.invalid/photo.webp");
    expect(imageUrl("javascript:alert(1)", "")).toBe("");
  });
  it("disables pages beyond the backend offset cap while retaining totals", () => {
    render(
      <Pagination
        page={500}
        size={20}
        totalPages={600}
        totalElements={12000}
        href={(value) => `/?page=${value}`}
      />,
    );
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    expect(
      screen.queryByRole("link", { name: "Page 502" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("12,000 matching places")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Page 501" })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });
  it("shows reports as allegations rather than verification", () => {
    render(<TrustBadge value="BROKERAGE_WARNED" />);
    expect(screen.getByText("Brokerage reported")).toBeInTheDocument();
    expect(screen.queryByText("Verified owner")).not.toBeInTheDocument();
  });
});
