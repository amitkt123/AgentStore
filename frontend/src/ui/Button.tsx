import styles from "./Button.module.css";

type Props = React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "ghost" | "danger";
  loading?: boolean;
};

export function Button({ variant = "primary", loading = false, disabled, children, className, ...rest }: Props) {
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={`${styles.btn} ${styles[variant]} ${className ?? ""}`}
    >
      {children}
    </button>
  );
}
