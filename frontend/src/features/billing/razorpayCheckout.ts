const SRC = "https://checkout.razorpay.com/v1/checkout.js";

interface RazorpayInstance { open(): void }
type RazorpayCtor = new (options: Record<string, unknown>) => RazorpayInstance;
declare global { interface Window { Razorpay?: RazorpayCtor } }

let loading: Promise<void> | null = null;

function loadScript(): Promise<void> {
  if (window.Razorpay) return Promise.resolve();
  loading ??= new Promise<void>((resolve, reject) => {
    const s = document.createElement("script");
    s.src = SRC;
    s.async = true;
    s.onload = () => resolve();
    s.onerror = () => { loading = null; s.remove(); reject(new Error("Could not load Razorpay Checkout")); };
    document.head.appendChild(s);
  });
  return loading;
}

/** Opens Razorpay Checkout with the server's options unchanged (amount is already in paise). */
export async function openRazorpayCheckout(checkout: Record<string, unknown>): Promise<"closed" | "paid"> {
  await loadScript();
  return new Promise((resolve) => {
    const modal = (checkout.modal as Record<string, unknown> | undefined) ?? {};
    const rzp = new window.Razorpay!({
      ...checkout,
      modal: { ...modal, ondismiss: () => resolve("closed") },
      handler: () => resolve("paid"),
    });
    rzp.open();
  });
}
