(ns garden.template
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as cheshire]
   [clojure.string :as str]))

;; adapted from https://github.com/babashka/neil

(def github-user (or (System/getenv "NEIL_GITHUB_USER")
                     (System/getenv "BABASHKA_NEIL_DEV_GITHUB_USER")))
(def github-token (or (System/getenv "NEIL_GITHUB_TOKEN")
                      (System/getenv "BABASHKA_NEIL_DEV_GITHUB_TOKEN")))

(defn curl-get-json [url]
  (let [response    (http/get url (merge {:throw false}
                                         (when (and github-user github-token)
                                           {:basic-auth [github-user github-token]})))
        parsed-body (-> response :body (cheshire/parse-string true))]
    (if (and (= 403 (:status response))
             (str/includes? url "api.github")
             (str/includes? (:message parsed-body) "rate limit"))
      (throw (ex-info "You've hit the GitHub rate-limit (60 reqs/hr).
  You can set the environment variables NEIL_GITHUB_USER to your GitHub user
  and NEIL_GITHUB_TOKEN to a GitHub API Token to increase the limit." {}))
      parsed-body)))

(defn default-branch [lib]
  (get (curl-get-json (format "https://api.github.com/repos/%s/%s"
                              (namespace lib) (name lib)))
       :default_branch))

(defn clean-github-lib [lib]
  (-> lib
      (str/replace "com.github." "")
      (str/replace "io.github." "")
      (symbol)))

(defn latest-github-sha [lib]
  (try (let [lib (clean-github-lib lib)
         branch (default-branch lib)]
     (get (curl-get-json (format "https://api.github.com/repos/%s/%s/commits/%s"
                                 (namespace lib) (name lib) branch))
          :sha))
       (catch clojure.lang.ExceptionInfo e
         (println (ex-message e)))))

(defn make-dep-entry [dep]
  (str (pr-str {(clean-github-lib dep) {:git/sha (or (latest-github-sha dep) "<sha>")}}) "\n"))

(defn make-require-entry [dep]
  (let [alias (last (str/split dep #"\."))]
    (format "[%s :as %s]" dep alias)))

(defn data-fn
  [{:keys [with-email with-id with-cron]}]
  (cond-> {}
    with-email (assoc :garden-email-dep (make-dep-entry "io.github.nextjournal/garden-email"))
    with-id (assoc :garden-id-dep (make-dep-entry "io.github.nextjournal/garden-id"))
    with-cron (assoc :garden-cron-dep (make-dep-entry "io.github.nextjournal/garden-cron"))
    with-email (assoc :garden-email-require (make-require-entry "nextjournal.garden-email"))
    with-id (assoc :garden-id-require (make-require-entry "nextjournal.garden-id"))
    with-cron (assoc :garden-cron-require (make-require-entry "nextjournal.garden-cron"))))

(data-fn {:with-email true})
