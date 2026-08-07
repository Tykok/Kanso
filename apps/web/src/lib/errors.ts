import { ApiError } from "./api";

/**
 * The sentence to put in front of someone when an action failed.
 *
 * The API answers with RFC 7807, and `ApiError.detail` is the field of that
 * document written for a person to read; `body` carries the rest of it, which
 * matters to the disposition modal and to nothing else. A failure that never
 * reached the server has only a technical message, and showing that is still
 * better than a menu that appears to do nothing.
 */
export function actionErrorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.detail || `Request failed (${error.status})`;
  if (error instanceof Error && error.message) return error.message;
  return "Something went wrong";
}
