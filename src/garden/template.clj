(ns garden.template
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [clojure.pprint :refer [pprint]]))

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

(def base-deps '{http-kit/http-kit {:mvn/version "2.8.0-SNAPSHOT"}
                 hiccup/hiccup {:mvn/version "2.0.0-RC3"}})

(def cron-deps '{io.github.nextjournal/garden-cron {:git/sha "23d5af087f2c76cc884273883b18d30cb4fa4997"}})

(def id-deps '{ring/ring-core {:mvn/version "2.0.0-alpha1"}
               io.github.nextjournal/garden-id {:git/sha "7c1c9bb36978bc098f477181bc0261d69fa44389"}})

(def email-deps '{io.github.nextjournal/garden-email {:git/sha "cdb5d404cd43127b3a495ca3f667279eab8950b7"}})

(def storage-requires "
    [clojure.java.io :as io]")

(def id-requires "
    ;; garden-id
    [ring.middleware.session :as session]
    [ring.middleware.session.cookie :refer [cookie-store]]
    [nextjournal.garden-id :as garden-id]")

(def email-requires "
    ;; garden-email
    [ring.middleware.params :as ring.params]
    [nextjournal.garden-email :as garden-email]
    [nextjournal.garden-email.render :as render-email]
    [nextjournal.garden-email.mock :as mock-email]")

(def cron-requires "
    ;; garden-cron
    [nextjournal.garden-cron :as garden-cron]")

(def cron-code-snippet "
;; increment a counter every 5 seconds
(defonce counter (atom 0))
(defn scheduled-task [_] (swap! counter inc))
(garden-cron/defcron #'scheduled-task {:second (range 0 60 5)})

(defn cron-fragment []
  [:div
   [:h2 \"Scheduled tasks\"]
   [:p \"Counter has been incremented \" @counter \" times, since the application started.\"]])
")

(def storage-code-snippet "
;; list persistent storage
(defn ls-storage []
  (.list (io/file (System/getenv \"GARDEN_STORAGE\"))))

(defn storage-fragment []
  [:div
   [:h2 \"Storage\"]
   [:p \"Persistent storage contains the following directories:\"]
   [:ul
    (for [d (ls-storage)]
      [:li [:pre d]])]])
")

(def id-code-snippet "
;; authenticate users
(defn auth-fragment [req]
  [:div
   [:h2 \"Auth\"]
   (if (garden-id/logged-in? req)
     [:div
      [:p \"You are logged in as:\"]
      [:pre (pr-str (garden-id/get-user req))]
      [:a {:href garden-id/logout-uri} \"logout\"]]
     [:div
      [:p \"You are not logged in.\"]
      [:a {:href garden-id/login-uri} \"login\"]])])
")

(def email-code-snippet "
;; send and receive email
(defn send-email! [req]
  (let [{:strs [to subject text html]} (:form-params req)]
    (html-response
     [:div
      [:pre (pr-str (garden-email/send-email! (cond-> {:to {:email to}}
                                                (not= \"\" subject) (assoc :subject subject)
                                                (not= \"\" text) (assoc :text text)
                                                (not= \"\" html) (assoc :html html))))]
      [:a {:href \"/\"} \"ok\"]])))

(defn email-fragment []
  [:div
   [:h2 \"Email\"]
   [:h3 \"Send email\"]
   [:form {:action \"/send-email\" :method \"POST\" :style \"display:flex;flex-direction:column;\"}
    [:label {:for \"to\"} \"to\"]
    [:input {:name \"to\" :type \"email\" :required true}]
    [:label {:for \"subject\"} \"subject\"]
    [:input {:name \"subject\" :type \"text\"}]
    [:label {:for \"text\"} \"plain text\"]
    [:textarea {:name \"text\"}]
    [:label {:for \"html\"} \"html email\"]
    [:textarea {:name \"html\"}]
    [:input {:type \"submit\" :value \"send\"}]]
   (when garden-email/dev-mode? [:a {:href mock-email/outbox-url} \"mock outbox\"])
   [:p \"You can send me email at \" [:a {:href (str \"mailto:\" garden-email/my-email-address)} garden-email/my-email-address]]
   [:div
    [:h3 \"Inbox\"]
    (render-email/render-mailbox (garden-email/inbox))]])
")

(def home-page-)


(defn make-deps [{:keys [with-email with-id with-cron]}]
  (cond-> base-deps
    with-email (merge email-deps)
    with-cron (merge cron-deps)
    with-id (merge id-deps)))

(defn make-requires [{:keys [with-storage with-email with-id with-cron]}]
  (cond-> ""
    with-storage (str storage-requires)
    with-email (str email-requires)
    with-cron (str cron-requires)
    with-id (str id-requires)))

(defn make-code-snippets [{:keys [with-storage with-email with-id with-cron]}]
  (cond-> ""
    with-storage (str storage-code-snippet)
    with-email (str email-code-snippet)
    with-cron (str cron-code-snippet)
    with-id (str id-code-snippet)))

(def cron-home-page-fragment "
   (cron-fragment)")
(def email-home-page-fragment "
   (email-fragment)")
(def storage-home-page-fragment "
   (storage-fragment)")
(def id-home-page-fragment "
   (auth-fragment req)")

(defn make-home-page-fragments [{:keys [with-storage with-email with-id with-cron]}]
  (cond-> ""
    with-storage (str storage-home-page-fragment)
    with-email (str email-home-page-fragment)
    with-cron (str cron-home-page-fragment)
    with-id (str id-home-page-fragment)))

(def email-extra-routes "

    \"/send-email\" (send-email! req)")

(defn make-extra-routes [{:keys [with-email]}]
  (cond-> ""
    with-email (str email-extra-routes)))

(def email-ring-middleware "
      ;; garden-email
      (ring.params/wrap-params)
      (garden-email/wrap-with-email)")

(def id-ring-middleware "
      ;; garden-id
      (garden-id/wrap-auth)
      (session/wrap-session {:store (cookie-store)})")

(defn make-ring-middleware [{:keys [with-email with-id]}]
  (cond-> ""
    with-email (str email-ring-middleware)
    with-id (str id-ring-middleware)))

(defn data-fn [flags]
  {:deps (with-out-str (pprint (make-deps flags)))
   :requires (make-requires flags)
   :code-snippets (make-code-snippets flags)
   :home-page-fragments (make-home-page-fragments flags)
   :extra-routes (make-extra-routes flags)
   :ring-middleware (make-ring-middleware flags)})
