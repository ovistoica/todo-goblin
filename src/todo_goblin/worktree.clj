(ns todo-goblin.worktree
  "Git worktree management for isolated AI development"
  (:require [babashka.process :as process]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn get-worktree-path
  "Generate a worktree path for a task"
  [base-path project-name task-id]
  (str base-path "/" project-name "/" task-id))

(defn ensure-base-path!
  "Ensure the base worktree directory exists"
  [base-path]
  (fs/create-dirs base-path))

(defn create-worktree
  "Create a new git worktree for a task"
  [repo-path worktree-path branch-name]
  (try
    (ensure-base-path! (str (io/file worktree-path) "/../"))

    ;; First, ensure we're in the repo directory and fetch latest
    (let [fetch-result (process/sh ["git" "-C" repo-path "fetch" "origin"])]
      (when-not (zero? (:exit fetch-result))
        (println "Warning: Failed to fetch from origin:" (:err fetch-result))))

    ;; Determine the default branch (main or master)
    (let [main-check (process/sh ["git" "-C" repo-path "rev-parse" "--verify" "origin/main"])
          master-check (process/sh ["git" "-C" repo-path "rev-parse" "--verify" "origin/master"])
          base-branch (cond
                        (zero? (:exit main-check)) "origin/main"
                        (zero? (:exit master-check)) "origin/master"
                        :else "HEAD")]

      ;; Create the worktree with a new branch based on the default branch
      (let [result (process/sh ["git" "-C" repo-path "worktree" "add" worktree-path "-b" branch-name base-branch])]
        (if (zero? (:exit result))
          {:success true
           :worktree-path worktree-path
           :branch-name branch-name}
          (do
            (println "Error creating worktree:" (:err result))
            {:success false :error (:err result)}))))
    (catch Exception e
      (println "Error executing git worktree add:" (.getMessage e))
      {:success false :error (.getMessage e)})))

(defn remove-worktree
  "Remove a git worktree"
  [worktree-path]
  (try
    (let [result (process/sh ["git" "worktree" "remove" worktree-path])]
      (if (zero? (:exit result))
        {:success true}
        (do
          (println "Error removing worktree:" (:err result))
          {:success false :error (:err result)})))
    (catch Exception e
      (println "Error executing git worktree remove:" (.getMessage e))
      {:success false :error (.getMessage e)})))

(defn list-worktrees
  "List all git worktrees in a repository"
  [repo-path]
  (try
    (let [result (process/sh ["git" "-C" repo-path "worktree" "list" "--porcelain"])]
      (if (zero? (:exit result))
        (->> (str/split (:out result) #"\n\n")
             (map (fn [entry]
                    (let [lines (str/split-lines entry)
                          path-line (first (filter #(str/starts-with? % "worktree ") lines))
                          branch-line (first (filter #(str/starts-with? % "branch ") lines))]
                      (when (and path-line branch-line)
                        {:path (subs path-line 9) ; Remove "worktree " prefix
                         :branch (subs branch-line 7)})))) ; Remove "branch " prefix
             (filter identity))
        (do
          (println "Error listing worktrees:" (:err result))
          [])))
    (catch Exception e
      (println "Error executing git worktree list:" (.getMessage e))
      [])))

(defn list-todo-goblin-worktrees
  "List worktrees managed by todo-goblin (those with 'tgbl/' prefix)"
  [repo-path]
  (->> (list-worktrees repo-path)
       (filter #(str/starts-with? (:branch %) "refs/heads/tgbl/"))))

(defn commit-and-push
  "Commit all changes in worktree and push to origin"
  [worktree-path branch-name commit-message]
  (try
    ;; Stage all changes
    (let [add-result (process/sh ["git" "-C" worktree-path "add" "."])]
      (when-not (zero? (:exit add-result))
        (println "Warning: git add failed:" (:err add-result))))

    ;; Check if there are changes to commit
    (let [status-result (process/sh ["git" "-C" worktree-path "status" "--porcelain"])]
      (if (and (zero? (:exit status-result))
               (not (str/blank? (:out status-result))))
        ;; There are changes, commit them
        (let [commit-result (process/sh ["git" "-C" worktree-path "commit" "-m" commit-message])]
          (if (zero? (:exit commit-result))
            ;; Push to origin
            (let [push-result (process/sh ["git" "-C" worktree-path "push" "origin" branch-name])]
              (if (zero? (:exit push-result))
                {:success true :message "Changes committed and pushed"}
                {:success false :error (str "Push failed: " (:err push-result))}))
            {:success false :error (str "Commit failed: " (:err commit-result))}))
        {:success true :message "No changes to commit"}))
    (catch Exception e
      {:success false :error (.getMessage e)})))

(defn cleanup-worktree
  "Clean up a worktree and its remote branch"
  [worktree-path branch-name repo-path]
  (try
    ;; Remove the worktree
    (let [remove-result (remove-worktree worktree-path)]
      (when (:success remove-result)
        ;; Delete the remote branch if it exists
        (let [delete-result (process/sh ["git" "-C" repo-path "push" "origin" "--delete" branch-name])]
          (when-not (zero? (:exit delete-result))
            (println "Warning: Failed to delete remote branch:" (:err delete-result)))))
      remove-result)
    (catch Exception e
      {:success false :error (.getMessage e)})))

(defn get-worktree-status
  "Get the status of a worktree (clean, dirty, etc.)"
  [worktree-path]
  (try
    (let [result (process/sh ["git" "-C" worktree-path "status" "--porcelain"])]
      (if (zero? (:exit result))
        (if (str/blank? (:out result))
          :clean
          :dirty)
        :error))
    (catch Exception e
      :error)))
