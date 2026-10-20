// The admin allowlist lives in an optional local module (admin-list.ts) that
// is not committed. If it is absent, nobody is an admin.
let admins: string[] = [];
try {
  // tslint:disable-next-line:no-var-requires
  admins = require("./admin-list").admins;
} catch (e) {
  console.warn("admin-list module not found; no admins configured");
}

export const isAdmin = (email?: string) =>
  !!email && admins.some((a) => a.toLowerCase() === email.toLowerCase());

export default admins;
