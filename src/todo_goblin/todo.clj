(ns todo-goblin.todo
  "TODO source parsing for org files and GitHub issues"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [todo-goblin.specs :as specs]))

(defn parse-org-line
  "Parse a single org-mode line to extract TODO information"
  [line line-num]
  (when-let [match (re-matches #"^\*+\s+(TODO|DONE|IN-PROGRESS)\s+(.+)$" (str/trim line))]
    (let [status (case (second match)
                   "TODO" :todo
                   "DONE" :done
                   "IN-PROGRESS" :in-progress)
          title (nth match 2)]
      {:status status
       :title title
       :line-num line-num})))

(defn extract-org-section
  "Extract the full section content for a TODO item"
  [lines start-line]
  (let [start-level (count (re-find #"^\*+" (nth lines start-line)))
        section-lines (loop [idx (inc start-line)
                             result []]
                        (if (or (>= idx (count lines))
                                (and (str/starts-with? (nth lines idx) "*")
                                     (<= (count (re-find #"^\*+" (nth lines idx))) start-level)))
                          result
                          (recur (inc idx) (conj result (nth lines idx)))))]
    (str/join "\n" section-lines)))

(defn read-org-file
  "Parse an org-mode file and extract TODO items"
  [file-path project-name]
  (try
    (when (.exists (io/file file-path))
      (let [content (slurp file-path)
            lines (str/split-lines content)]
        (->> lines
             (map-indexed (fn [idx line]
                            (when-let [parsed (parse-org-line line idx)]
                              (let [description (extract-org-section lines idx)]
                                (specs/make-task
                                 {:id (str "org-" project-name "-" idx)
                                  :title (:title parsed)
                                  :description description
                                  :source :org
                                  :source-id (str "line-" (:line-num parsed))
                                  :status (:status parsed)
                                  :project-name project-name})))))
             (filter identity)
             (filter #(= (:status %) :todo))))) ; Only return TODO items
    (catch Exception e
      (println "Error reading org file" file-path ":" (.getMessage e))
      [])))

(defn get-github-tasks
  "Get tasks from GitHub issues with specific label"
  [project-config project-name github-list-issues-fn]
  (let [repo (:github/repo project-config)
        label (:github/issues-label project-config "todo")]
    (->> (github-list-issues-fn repo label)
         (map #(assoc % :project-name project-name)))))

(defn get-org-tasks
  "Get tasks from org file"
  [project-config project-name]
  (let [cwd (:cwd project-config)
        todo-file (:todo/file project-config)
        full-path (str cwd "/" todo-file)]
    (read-org-file full-path project-name)))

(defn get-tasks
  "Get all tasks for a project based on its configuration"
  [project-config project-name & {:keys [github-list-issues-fn]
                                  :or {github-list-issues-fn (constantly [])}}]
  (case (:todo/type project-config)
    :org (get-org-tasks project-config project-name)
    :github (get-github-tasks project-config project-name github-list-issues-fn)
    []))

(defn filter-available-tasks
  "Filter tasks that are available for selection (no existing PRs or worktrees)"
  [tasks existing-prs existing-worktrees]
  (let [pr-task-ids (set (map :task-id existing-prs)) ; Assumes PRs have task-id
        worktree-task-ids (set (map :task-id existing-worktrees))] ; Assumes worktrees have task-id
    (->> tasks
         (filter #(not (contains? pr-task-ids (:id %))))
         (filter #(not (contains? worktree-task-ids (:id %)))))))

(defn select-task
  "Simple task selection strategy - picks the first available task"
  [available-tasks]
  (first available-tasks))

(defn generate-task-branch-name
  "Generate a git branch name for a task"
  [task]
  (let [safe-title (-> (:title task)
                       (str/lower-case)
                       (str/replace #"[^a-z0-9\s]" "")
                       (str/replace #"\s+" "-")
                       (str/trim))]
    (str "tgbl/" (subs safe-title 0 (min 30 (count safe-title))) "-" (subs (:id task) 0 8))))

(defn task-summary
  "Generate a human-readable summary of a task"
  [task]
  (str "Task: " (:title task) "\n"
       "ID: " (:id task) "\n"
       "Source: " (name (:source task)) "\n"
       "Status: " (name (:status task)) "\n"
       "Description:\n" (:description task)))

(defn tasks-summary
  "Generate a summary of multiple tasks"
  [tasks]
  (if (empty? tasks)
    "No tasks found."
    (str "Found " (count tasks) " tasks:\n\n"
         (str/join "\n---\n" (map task-summary tasks)))))
