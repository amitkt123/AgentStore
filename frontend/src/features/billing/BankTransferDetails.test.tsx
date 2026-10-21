import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { BankTransferDetails } from "./BankTransferDetails";

it("copies a value", async () => {
  const writeText = vi.fn().mockResolvedValue(undefined);
  const user = userEvent.setup();
  Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
  render(<BankTransferDetails details={{ accountNumber: "VA1", ifsc: "RAZR1", beneficiaryName: "Karya" }} />);
  await user.click(screen.getByRole("button", { name: "Copy IFSC" }));
  expect(writeText).toHaveBeenCalledWith("RAZR1");
});
