import { functions } from "./api.js";

// Asks the API which of the given creation ids the signed-in user has already
// voted for. Resolves to an empty Set when signed out or on any error.
export async function fetchVotedIds(ids) {
  const user = window.firebase.auth().currentUser;
  const unique = Array.from(new Set(ids)).filter(Boolean);
  if (!user || unique.length === 0) {
    return new Set();
  }
  try {
    const token = await user.getIdToken();
    const res = await fetch(
      functions._url(`api/votes?ids=${unique.slice(0, 200).join(",")}`),
      { headers: { Authorization: "Bearer " + token } }
    );
    if (!res.ok) {
      return new Set();
    }
    const data = await res.json();
    return new Set(Array.isArray(data.ids) ? data.ids : []);
  } catch (e) {
    console.error(e);
    return new Set();
  }
}
