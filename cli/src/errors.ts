/**
 * A failure the person running the CLI should see as one readable line. Exit code 1 means the project or the build
 * is bad; 2 means the tool could not do its job (usage, network, a rejected upload, a timeout).
 */
export class CliError extends Error {
  constructor(message: string, readonly exitCode: 1 | 2 = 2) {
    super(message);
    this.name = "CliError";
  }
}
