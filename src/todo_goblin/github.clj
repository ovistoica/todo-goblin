(ns todo-goblin.github
  "GitHub CLI operations for todo-goblin"
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [todo-goblin.specs :as specs]))

(defn check-auth
  "Check if user is authenticated with gh CLI"
  []
  (try
    (let [result (process/sh ["gh" "auth" "status"])]
      (zero? (:exit result)))
    (catch Exception e
      (println "GitHub CLI authentication check failed:" (.getMessage e))
      false)))

(defn ensure-logged-in!
  "Ensure user is logged in with gh CLI, exit if not"
  []
  (when-not (check-auth)
    (println "Error: Not logged in to GitHub CLI.")
    (println "Please run: gh auth login")
    (System/exit 1)))

(defn parse-json-output
  "Parse JSON output from gh CLI command"
  [output]
  (try
    (when-not (str/blank? output)
      (json/parse-string output true))
    (catch Exception e
      (println "Error parsing JSON output:" (.getMessage e))
      [])))

(defn list-open-prs
  "List all open pull requests for a repository"
  [repo]
  (try
    (let [result (process/sh ["gh" "pr" "list"
                              "--repo" repo
                              "--json" "number,title,state,isDraft,headRefName,url"])]
      (if (zero? (:exit result))
        (->> (parse-json-output (:out result))
             (map (fn [pr-data]
                    (specs/make-pull-request
                     {:pr-number (:number pr-data)
                      :pr-title (:title pr-data)
                      :pr-state (keyword (str/lower-case (:state pr-data)))
                      :pr-is-draft (:isDraft pr-data)
                      :pr-branch (:headRefName pr-data)
                      :pr-url (:url pr-data)
                      :project-name nil})))) ; Will be set by caller
        (do
          (println "Error listing PRs:" (:err result))
          [])))
    (catch Exception e
      (println "Error executing gh pr list:" (.getMessage e))
      [])))

(defn list-draft-prs
  "List only draft pull requests for a repository"
  [repo]
  (->> (list-open-prs repo)
       (filter :pr/is-draft)))

(defn create-draft-pr
  "Create a draft pull request"
  [repo branch title body]
  (try
    (let [result (process/sh ["gh" "pr" "create"
                              "--repo" repo
                              "--draft"
                              "--base" "main"
                              "--head" branch
                              "--title" title
                              "--body" body])]
      (if (zero? (:exit result))
        (let [pr-url (str/trim (:out result))
              pr-number (-> pr-url (str/split #"/") last)]
          {:success true
           :pr-number (Integer/parseInt pr-number)
           :pr-url pr-url})
        (do
          (println "Error creating draft PR:" (:err result))
          {:success false :error (:err result)})))
    (catch Exception e
      (println "Error executing gh pr create:" (.getMessage e))
      {:success false :error (.getMessage e)})))

(defn update-pr-title
  "Update the title of a pull request"
  [repo pr-number new-title]
  (try
    (let [result (process/sh ["gh" "pr" "edit"
                              "--repo" repo
                              (str pr-number)
                              "--title" new-title])]
      (if (zero? (:exit result))
        {:success true}
        (do
          (println "Error updating PR title:" (:err result))
          {:success false :error (:err result)})))
    (catch Exception e
      (println "Error executing gh pr edit:" (.getMessage e))
      {:success false :error (.getMessage e)})))

(defn list-issues
  "List GitHub issues with a specific label"
  [repo label]
  (try
    (let [result (process/sh ["gh" "issue" "list"
                              "--repo" repo
                              "--label" label
                              "--state" "open"
                              "--json" "number,title,body,labels"])]
      (if (zero? (:exit result))
        (->> (parse-json-output (:out result))
             (map (fn [issue-data]
                    (specs/make-task
                     {:id (str "github-" (:number issue-data))
                      :title (:title issue-data)
                      :description (or (:body issue-data) "")
                      :source :github
                      :source-id (str (:number issue-data))
                      :status :todo
                      :project-name nil})))) ; Will be set by caller
        (do
          (println "Error listing issues:" (:err result))
          [])))
    (catch Exception e
      (println "Error executing gh issue list:" (.getMessage e))
      [])))

(defn get-repo-info
  "Get basic repository information"
  [repo]
  (try
    (let [result (process/sh ["gh" "repo" "view" repo "--json" "name,owner,defaultBranchRef"])]
      (if (zero? (:exit result))
        (parse-json-output (:out result))
        (do
          (println "Error getting repo info:" (:err result))
          nil)))
    (catch Exception e
      (println "Error executing gh repo view:" (.getMessage e))
      nil)))

(defn pr-title-formats
  "Generate PR title formats based on status"
  [task status & {:keys [timestamp]}]
  (let [ts (or timestamp (java.time.LocalDateTime/now))
        date-str (.format ts (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd"))
        task-title (:title task)]
    (case status
      :draft (str "🤖[AI TASK STARTED] " task-title " " date-str)
      :complete (str "✅[AI TASK COMPLETE] " task-title " " date-str)
      :failed (str "❌[AI TASK FAILED] " task-title " " date-str)
      task-title)))
