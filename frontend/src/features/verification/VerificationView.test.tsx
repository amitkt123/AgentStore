import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const save = vi.hoisted(() => vi.fn());
const upload = vi.hoisted(() => vi.fn());
const submit = vi.hoisted(() => vi.fn());
vi.mock("@/app/(app)/developer/verification/actions", () => ({
  saveStepAction: (...a: unknown[]) => save(...a),
  uploadPanAction: (...a: unknown[]) => upload(...a),
  submitAction: (...a: unknown[]) => submit(...a),
}));
import { VerificationView } from "./VerificationView";
import { FIELD_TEXT } from "./errors";
import { view } from "./fixtures";

const complete = view({
  currentStep: "AGREEMENT", entityType: "INDIVIDUAL", legalName: "Asha Kulkarni", displayName: "Asha Kulkarni", country: "IN", city: "Pune",
  panMasked: "234L", panDocumentUploaded: true, phoneMasked: "2208",
});
const pick = (name: RegExp) => userEvent.click(screen.getByRole("button", { name }));
const cont = () => userEvent.click(screen.getByRole("button", { name: /^(continue|submit for review)$/i }));

beforeEach(() => { save.mockReset(); upload.mockReset(); submit.mockReset(); });

it("shows done, current and unreachable steps from the saved state", () => {
  render(<VerificationView initial={view({ ...complete, currentStep: "PHONE", phoneMasked: null })} />);
  expect(screen.getByRole("button", { name: /About you/ })).toHaveAttribute("data-state", "done");
  expect(screen.getByRole("button", { name: /Tax IDs/ })).toHaveAttribute("data-state", "done");
  expect(screen.getByRole("button", { name: /Phone/ })).toHaveAttribute("aria-current", "step");
  expect(screen.getByRole("button", { name: /Developer agreement/ })).toBeDisabled();
  expect(screen.getByText("Karya checks it")).toBeInTheDocument();
});

it("asks for each missing About-you field and saves nothing", async () => {
  render(<VerificationView initial={view()} />);
  await cont();
  expect(screen.getByText(FIELD_TEXT.legalName)).toBeInTheDocument();
  expect(screen.getByText(FIELD_TEXT.displayName)).toBeInTheDocument();
  expect(screen.getByText(FIELD_TEXT.city)).toBeInTheDocument();
  expect(save).not.toHaveBeenCalled();
});

it("saves About you with a company type and moves on to Tax IDs", async () => {
  save.mockResolvedValue({ view: view({ currentStep: "TAX_IDS", entityType: "LLP", legalName: "Asha LLP", displayName: "Asha", country: "IN", city: "Pune" }) });
  render(<VerificationView initial={view()} />);
  await userEvent.click(screen.getByRole("button", { name: "A company" }));
  await userEvent.selectOptions(screen.getByLabelText("Type of company"), "LLP");
  await userEvent.type(screen.getByLabelText("Legal name, as on your PAN"), "Asha LLP");
  await userEvent.type(screen.getByLabelText("Name shown on listings"), "Asha");
  await userEvent.type(screen.getByLabelText("City"), "Pune");
  await cont();
  expect(save).toHaveBeenCalledWith("ABOUT", { entityType: "LLP", legalName: "Asha LLP", displayName: "Asha", country: "IN", city: "Pune" });
  expect(await screen.findByRole("heading", { name: "Tax IDs" })).toBeInTheDocument();
});

it("shows saved IDs masked, never as an input, and sends an upper-cased replacement", async () => {
  save.mockResolvedValue({ view: complete });
  render(<VerificationView initial={complete} />);
  await pick(/Tax IDs/);
  expect(screen.getByText("•••• 234L")).toBeInTheDocument();
  expect(screen.queryByLabelText("PAN")).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Replace PAN" }));
  await userEvent.type(screen.getByLabelText("PAN"), "abcde1234f");
  await cont();
  expect(save).toHaveBeenCalledWith("TAX_IDS", { pan: "ABCDE1234F" });
  await screen.findByRole("heading", { name: "Phone" });
  await pick(/Tax IDs/);
  expect(screen.queryByDisplayValue("ABCDE1234F")).not.toBeInTheDocument();
  expect(screen.getByText("•••• 234L")).toBeInTheDocument();
});

it("strips spaces from the phone number it sends", async () => {
  save.mockResolvedValue({ view: complete });
  render(<VerificationView initial={view({ ...complete, currentStep: "PHONE", phoneMasked: null })} />);
  await userEvent.type(screen.getByLabelText("Mobile number"), "+91 99200 12208");
  await cont();
  expect(save).toHaveBeenCalledWith("PHONE", { phone: "+919920012208" });
});

it("puts a server field error under the input", async () => {
  save.mockResolvedValue({ failure: { text: FIELD_TEXT.pan, field: "pan" } });
  render(<VerificationView initial={view({ currentStep: "TAX_IDS", entityType: "INDIVIDUAL", legalName: "A", displayName: "A", country: "IN", city: "P" })} />);
  await userEvent.type(screen.getByLabelText("PAN"), "BADPAN");
  await cont();
  expect(await screen.findByText(FIELD_TEXT.pan)).toBeInTheDocument();
});

it("uploads the PAN image, and refuses one over 5 MB without calling the server", async () => {
  upload.mockResolvedValue({ view: complete });
  render(<VerificationView initial={view({ ...complete, panDocumentUploaded: false, currentStep: "TAX_IDS" })} />);
  await userEvent.upload(screen.getByLabelText(/PAN card image/), new File(["x"], "pan.pdf", { type: "application/pdf" }));
  expect(upload).toHaveBeenCalledTimes(1);
  expect((upload.mock.calls[0][0] as FormData).get("file")).toBeInstanceOf(File);
  await userEvent.upload(screen.getByLabelText(/PAN card image/), new File([new ArrayBuffer(5 * 1024 * 1024 + 1)], "big.pdf", { type: "application/pdf" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("over 5 MB");
  expect(upload).toHaveBeenCalledTimes(1);
});

it("does not save twice on a double click", async () => {
  let resolve!: (v: unknown) => void;
  save.mockReturnValue(new Promise((r) => { resolve = r; }));
  render(<VerificationView initial={complete} />);
  await pick(/Phone/);
  await userEvent.dblClick(screen.getByRole("button", { name: /^continue$/i }));
  expect(save).toHaveBeenCalledTimes(1);
  resolve({ view: complete });
  expect(await screen.findByRole("heading", { name: "Developer agreement" })).toBeInTheDocument();
});

it("blocks the submit and says what is still needed", async () => {
  render(<VerificationView initial={view({ ...complete, phoneMasked: null })} />);
  expect(screen.getByRole("button", { name: "Submit for review" })).toBeDisabled();
  expect(screen.getByText(/Still needed: Phone, Developer agreement\./)).toBeInTheDocument();
});

it("saves the agreement and then submits for review", async () => {
  save.mockResolvedValue({ view: view({ ...complete, agreementAcceptedAt: "2026-09-30T00:00:00Z" }) });
  submit.mockResolvedValue({ view: view({ ...complete, status: "PENDING", agreementAcceptedAt: "2026-09-30T00:00:00Z" }) });
  render(<VerificationView initial={complete} />);
  expect(screen.getByRole("button", { name: "Submit for review" })).toBeDisabled();
  await userEvent.click(screen.getByRole("checkbox", { name: /accept it/ }));
  await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));
  expect(save).toHaveBeenCalledWith("AGREEMENT", { agreementVersion: "2.1" });
  expect(submit).toHaveBeenCalledTimes(1);
  expect(await screen.findByText(/Sent to Karya/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /submit for review/i })).not.toBeInTheDocument();
});

it("jumps to the first missing step when the server says the submit is incomplete", async () => {
  save.mockResolvedValue({ view: view({ ...complete, agreementAcceptedAt: "2026-09-30T00:00:00Z" }) });
  submit.mockResolvedValue({ failure: { text: "Finish these first: Phone.", missingSteps: ["PHONE"] } });
  render(<VerificationView initial={complete} />);
  await userEvent.click(screen.getByRole("checkbox", { name: /accept it/ }));
  await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));
  expect(await screen.findByRole("heading", { name: "Phone" })).toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent("Finish these first: Phone.");
});

it.each(["PENDING", "VERIFIED", "REJECTED"] as const)("shows %s read-only", (status) => {
  render(<VerificationView initial={view({ ...complete, status, agreementAcceptedAt: "2026-09-30T00:00:00Z" })} />);
  expect(screen.getByLabelText("Legal name, as on your PAN")).toBeDisabled();
  expect(screen.queryByRole("button", { name: /continue|submit for review/i })).not.toBeInTheDocument();
  expect(screen.getByTestId("banner")).toBeInTheDocument();
});

it("shows the reviewer's note when there is one, and a plain sentence when there is not", () => {
  const { unmount } = render(<VerificationView initial={view({ ...complete, status: "FIX_REQUESTED", decisionNote: "PAN image is blurry" })} />);
  expect(screen.getByTestId("banner")).toHaveTextContent("PAN image is blurry");
  expect(screen.getByRole("button", { name: "Submit for review" })).toBeInTheDocument();
  unmount();
  render(<VerificationView initial={view({ ...complete, status: "FIX_REQUESTED" })} />);
  expect(screen.getByTestId("banner")).toHaveTextContent("Karya asked for a change");
  expect(screen.getByTestId("banner")).not.toHaveTextContent("undefined");
});
