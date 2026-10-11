import React from "react";
import { NavLink, withRouter } from "react-router-dom";

import timeago from "timeago.js";

import { functions } from "../api.js";
import SignInScreen from "./signin.js";
import { Post } from "./Post";
import { fetchVotedIds } from "../votes.js";

export const ago = timeago();

export let storageUrl =
  "https://firebasestorage.googleapis.com/v0/b/sandtable-8d0f7.appspot.com/o/creations%2F";

class Submissions extends React.Component {
  shouldComponentUpdate(nextProps) {
    let { submissions, browseVotes } = this.props;
    return (
      nextProps.submissions !== submissions ||
      nextProps.browseVotes !== browseVotes
    );
  }
  render() {
    let { submissions, voteFromBrowse, browseVotes, report, checkVotes } =
      this.props;

    if (submissions === null) {
      return <div style={{ height: "90vh" }}>Loading Submissions...</div>;
    }
    if (!Array.isArray(submissions)) {
      return (
        <div style={{ height: "90vh" }}>
          Couldn't load submissions. Please try again later.
        </div>
      );
    }
    if (submissions.length == 0) {
      return <div style={{ height: "90vh" }}>Didn't find anything!</div>;
    }

    return (
      <div className="submissions">
        {submissions.map((submission) => {
          return (
            <Post
              key={submission.id}
              submission={submission}
              voteFromBrowse={voteFromBrowse}
              browseVotes={browseVotes}
              report={report}
              checkVotes={checkVotes}
            />
          );
        })}
      </div>
    );
  }
}

class Browse extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      paused: false,
      submitting: false,
      dataURL: {},
      submissions: null,
      browseVotes: {},
      search: "",
    };
    this.loadRequestId = 0;
  }
  componentWillMount() {
    this.loadSubmissions();
  }
  componentDidMount() {
    // auth resolves asynchronously after page load; once we know who the user
    // is, mark the posts they've already liked.
    this.unregisterAuthObserver = firebase.auth().onAuthStateChanged((user) => {
      if (user && Array.isArray(this.state.submissions)) {
        this.checkVotes(this.state.submissions.map((s) => s.id));
      }
    });
  }
  componentWillUnmount() {
    if (this.unregisterAuthObserver) {
      this.unregisterAuthObserver();
    }
  }
  componentDidUpdate(prevProps) {
    if (
      prevProps.location.pathname != this.props.location.pathname ||
      prevProps.location.search != this.props.location.search
    ) {
      this.loadSubmissions();
    }
  }
  togglePause() {
    window.paused = !this.state.paused;
    this.setState({ paused: !this.state.paused });
  }

  setSize(event, size) {
    event.preventDefault();
    this.setState({
      size,
    });
  }

  loadSubmissions() {
    let { location } = this.props;
    if (location.search.startsWith("?title=")) {
      // to load deep urls with a search query.
      let search = location.search.slice(7);
      try {
        search = decodeURIComponent(search);
      } catch (e) {
        // leave as-is if malformed
      }
      this.setState({ search });
    }
    let param = "";

    if (location.pathname.startsWith("/browse/top/")) {
      param = "?q=score";
    }
    if (location.pathname.startsWith("/browse/top/day")) {
      param = "?q=score&d=1";
    }
    if (location.pathname.startsWith("/browse/top/week")) {
      param = "?q=score&d=7";
    }
    if (location.pathname.startsWith("/browse/top/month")) {
      param = "?q=score&d=30";
    }
    if (location.pathname.startsWith("/browse/search/")) {
      param = location.search;
    }

    const requestId = ++this.loadRequestId;
    this.setState({ submissions: null });
    fetch(functions._url("api/creations") + param, {
      method: "GET",
      headers: {
        "Content-Type": "application/json",
      },
    })
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then((response) => {
        // ignore responses from requests that have since been superseded
        if (requestId !== this.loadRequestId) return;
        this.setState({ submissions: response });
        if (Array.isArray(response)) {
          this.checkVotes(response.map((s) => s.id));
        }
      })
      .catch((error) => {
        if (requestId !== this.loadRequestId) return;
        this.setState({ submissions: false });
        console.error("Error:", error);
      });
  }

  // Marks any of the given ids that the current user has already liked.
  checkVotes(ids) {
    fetchVotedIds(ids).then((voted) => {
      if (voted.size === 0) return;
      this.setState(({ browseVotes }) => {
        const next = { ...browseVotes };
        voted.forEach((id) => {
          if (next[id] === undefined) next[id] = true;
        });
        return { browseVotes: next };
      });
    });
  }

  voteFromBrowse(submission) {
    // creations/:id/vote
    const { currentUser } = firebase.auth();
    if (!currentUser) {
      window.alert("Please sign in to vote!");
      return;
    }
    if (!currentUser.emailVerified) {
      window.alert(`Please verify your email ${currentUser.email} to vote!`);
      return;
    }
    if (this.state.browseVotes[submission.id]) {
      return;
    }
    const previousVotes = this.state.browseVotes;
    this.setState(({ browseVotes }) => ({
      browseVotes: {
        ...browseVotes,
        [submission.id]: submission.data.score + 1,
      },
    }));
    currentUser
      .getIdToken()
      .then((token) =>
        fetch(functions._url(`api/creations/${submission.id}/vote`), {
          method: "PUT",
          headers: {
            "Content-Type": "application/json",
            Authorization: "Bearer " + token,
          },
        })
      )
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then((data) => {
        this.setState(({ browseVotes }) => ({
          browseVotes: { ...browseVotes, [submission.id]: data.score },
        }));
      })
      .catch((e) => {
        console.error(e);
        // roll back the optimistic vote
        this.setState({ browseVotes: previousVotes });
      });
  }
  report(id) {
    const { currentUser } = firebase.auth();
    if (!currentUser) {
      window.alert("Please sign in to report posts!");
      return;
    }
    currentUser
      .getIdToken()
      .then((token) => {
        fetch(functions._url(`api/creations/${id}/report`), {
          method: "PUT",
          headers: {
            "Content-Type": "application/json",
            Authorization: "Bearer " + token,
          },
        })
          .then((res) => res.json())
          .then((data) => {
            // this.setState(({ browseVotes }) => ({
            //   browseVotes: { [submission.id]: data.score, ...browseVotes }
            // }));
          })
          .catch((e) => {
            console.error(e);
          });
      });
  }
  render() {
    const { search, submissions, browseVotes } = this.state;
    return (
      <React.Fragment>
        <SignInScreen />
        <p style={{ gridColumn: "auto / span 2", margin: "8px", fontSize: 16 }}>
          Check out ☞<br></br>
          <a href="https://studio.sandspiel.club" target="_blank">
            {" "}
            <b> SANDSPIEL STUDIO: Invent New Elements!</b>
          </a>
          <br></br>
          <a href="https://orb.farm" target="_blank">
            {" "}
            Orb.Farm
          </a>
          {"          \xa0        \xa0\xa0\xa0    "}
          <br></br>
          <a href="https://www.youtube.com/watch?v=2qfjJ-0ZeVM" target="_blank">
            {" "}
            "Top 9 ways to make Water"
          </a>
        </p>
        <NavLink exact to="/browse/">
          <button>New</button>
        </NavLink>
        <NavLink to="/browse/top/day/">
          <button>Day</button>
        </NavLink>
        <NavLink to="/browse/top/week/">
          <button>Week</button>
        </NavLink>
        <NavLink to="/browse/top/month/">
          <button>Month</button>
        </NavLink>
        <NavLink exact to="/browse/top/">
          <button>Year </button>
        </NavLink>
        <span style={{ display: "inline-block" }}>
          <input
            value={search}
            onChange={(e) => this.setState({ search: e.target.value })}
            onKeyDown={(e) =>
              e.keyCode == 13 && // I think that's enter
              search &&
              this.props.history.push(
                `/browse/search/?title=${encodeURIComponent(search)}`
              )
            }
            placeholder="search"
          />
          {search && (
            <NavLink
              to={{
                pathname: "/browse/search/",
                search: `?title=${encodeURIComponent(search)}`,
              }}
            >
              <button>Search</button>
            </NavLink>
          )}
        </span>
        <Submissions
          submissions={submissions}
          voteFromBrowse={(submission) => this.voteFromBrowse(submission)}
          browseVotes={browseVotes}
          report={(id) => this.report(id)}
          checkVotes={(ids) => this.checkVotes(ids)}
        />
      </React.Fragment>
    );
  }
}

export default withRouter(Browse);
