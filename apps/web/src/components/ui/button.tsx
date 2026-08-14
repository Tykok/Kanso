import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { Slot } from "radix-ui"

import { cn } from "@/lib/utils"

const buttonVariants = cva(
  "inline-flex shrink-0 items-center justify-center gap-2 rounded-md text-12 font-medium whitespace-nowrap transition-all outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        // "Principal": one per zone, no border of its own.
        default: "bg-primary text-primary-foreground hover:bg-primary/90",
        destructive:
          // "Destructif": no fill until hover — a slip of the mouse costs nothing.
          // Amber is kept for what is broken but repairable, red for what destroys —
          // globals.css:39 records the rule this variant is the button-shaped half of.
          "text-urgent hover:bg-urgent/10",
        // "Secondaire": a bordered, resting surface.
        outline:
          "border bg-card shadow-flat hover:bg-accent hover:text-accent-foreground dark:border-input dark:bg-input/30 dark:hover:bg-input/50",
        secondary:
          "bg-secondary text-secondary-foreground hover:bg-secondary/80",
        // "Discret": text alone until hover.
        ghost:
          "hover:bg-accent hover:text-accent-foreground dark:hover:bg-accent/50",
        link: "text-primary underline-offset-4 hover:underline",
      },
      size: {
        // 28px: the height a "Principal"/"Secondaire" button draws at throughout the
        // bundle (padding 6px 14px around a 12px line), not shadcn's 36px default.
        default: "h-7 px-3.5 has-[>svg]:px-3",
        xs: "h-5 gap-1 rounded-sm px-1.5 text-11 has-[>svg]:px-1 [&_svg:not([class*='size-'])]:size-3",
        sm: "h-6 gap-1.5 rounded-md px-2.5 has-[>svg]:px-2",
        lg: "h-8 rounded-md px-5 has-[>svg]:px-3.5",
        icon: "size-7",
        "icon-xs": "size-5 rounded-sm [&_svg:not([class*='size-'])]:size-3",
        "icon-sm": "size-6",
        "icon-lg": "size-8",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

function Button({
  className,
  variant = "default",
  size = "default",
  asChild = false,
  ...props
}: React.ComponentProps<"button"> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean
  }) {
  const Comp = asChild ? Slot.Root : "button"

  return (
    <Comp
      data-slot="button"
      data-variant={variant}
      data-size={size}
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  )
}

export { Button, buttonVariants }
