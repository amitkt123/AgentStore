import styles from "./Glass.module.css";

type Props = { level?: 1 | 2 | 3; as?: "div" | "section" | "aside" | "header" | "nav" | "li"; className?: string } & React.HTMLAttributes<HTMLElement>;

/** A translucent surface. Nested Glass drops its own blur so nothing is blurred twice. */
export function Glass({ level = 1, as: Tag = "div", className, children, ...rest }: Props) {
  return <Tag className={`${styles.surface} ${styles[`l${level}`]} ${className ?? ""}`} {...rest}>{children}</Tag>;
}
