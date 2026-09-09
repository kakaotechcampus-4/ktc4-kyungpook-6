 ## Required Workflow

Read the target frame with Figma MCP before implementing a page or state.
Treat Figma MCP frame/node data as the source of truth for layout, color, font size, font weight, spacing, and dimensions.
Do not implement from screenshots alone. Screenshots are only visual references after the node data is read.
If screenshot details and Figma MCP data disagree, prefer Figma MCP data.
Use Tailwind classes for colors, typography, spacing, border radius, borders, shadows, and layout.
Use responsive layout primitives such as flex, grid, w-full, max-w-*, and min-h-screen unless the Figma element is explicitly fixed-size.
Implement icon nodes named like icon-set:icon-name with @iconify/react.
Implement stateful screens only when an equivalent Figma frame/node exists.
For API-driven text, use src/components/ui/Skeleton.tsx at the text-node level while loading.
Do not replace static labels, icons, buttons, frames, or whole components with Skeletons.
