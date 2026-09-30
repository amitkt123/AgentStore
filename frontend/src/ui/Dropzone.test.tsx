import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Dropzone } from "./Dropzone";

it("passes the chosen file to onFile", async () => {
  const onFile = vi.fn();
  render(<Dropzone label="Upload file" onFile={onFile} />);
  const file = new File(["x"], "a.csv", { type: "text/csv" });
  await userEvent.upload(screen.getByLabelText("Upload file"), file);
  expect(onFile).toHaveBeenCalledWith(file);
});
