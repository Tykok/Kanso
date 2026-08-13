import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/** Joins class names and lets a later Tailwind utility beat an earlier conflicting one,
 *  which is what makes a `className` prop able to override a component's own defaults. */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
