#!/usr/bin/env node
import { main } from "./cli.js";
import { createContext } from "./context.js";

main(process.argv.slice(2), createContext()).then(
  (code) => {
    process.exitCode = code;
  },
  (error) => {
    console.error(error instanceof Error ? error.stack : error);
    process.exitCode = 2;
  },
);
