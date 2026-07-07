import type { FintrackApiError } from "../api-client.js";

export interface ToolTextContent {
  type: "text";
  text: string;
}

export interface ToolResult {
  // Index signature required to structurally match the SDK's CallToolResult type.
  [x: string]: unknown;
  content: ToolTextContent[];
  isError?: boolean;
}

/**
 * Wraps API data as structured JSON tool output. Callers must never interpolate this data
 * into instruction-like prose — financial data (transaction descriptions, merchant names)
 * is user/statement-derived and must be treated as data, never as instructions to the model.
 */
export function toToolResult(data: unknown): ToolResult {
  return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
}

export function toErrorResult(error: FintrackApiError): ToolResult {
  return { content: [{ type: "text", text: error.message }], isError: true };
}
