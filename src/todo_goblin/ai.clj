(ns todo-goblin.ai
  "Claude Code CLI integration for AI task execution"
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(def claude-code-prompt
  "Base prompt template for Claude Code"
  "You are a code assistant that every day looks at the active todo list and active TODOs, picks one TODO and proposes a fix for it. Once you decide on one task, open a draft PR to showcase you are working on it, to avoid conflicts with other people or AIs choosing the same task. You will follow the same rule too. If a draft PR is open for a task, it means somebody is already working on it.

When you implement a task, make sure to write tests for that task, respecting the notion of *significant other file*.

A significant other file is a file that is related to the main file. The logic for finding significant other files is described below:

1. For the file =src/clj/saas/core.clj= the significant other file can be =test/clj/saas/core_test.clj=. This file contains all the relevant tests for that particular namespace.
2. For the file =src/cljc/saas/ui/button.cljc= one significant other file can be =portfolio/saas/ui/button_scenes.cljs= that define UI scenes for the button, that showcase how it renders based on different props. =button.cljc= can have multiple significant other files. =test/cljc/saas/ui/button_test.cljc= can also be a significant other file. In the =button_scenes.cljs= we concern ourselves with how the button /looks/, in the tests, we concern ourselves with how the button /behaves/.
3. You should always add tests and make sure they pass for your new functionality.

Your current task is:")

(defn generate-task-prompt
  "Generate a complete prompt for Claude Code with task context"
  [task context-files]
  (str claude-code-prompt "\n\n"
       "TASK: " (:title task) "\n"
       "DESCRIPTION: " (:description task) "\n"
       "TASK ID: " (:id task) "\n\n"
       (when (seq context-files)
         (str "RELEVANT CONTEXT FILES:\n"
              (str/join "\n" (map #(str "- " %) context-files))
              "\n\n"))
       "Please implement this task following the guidelines above. Make sure to write comprehensive tests and ensure they pass."))

(defn check-claude-code-available
  "Check if Claude Code CLI is available"
  []
  (try
    (let [result (process/sh ["claude-code" "--version"])]
      (zero? (:exit result)))
    (catch Exception e
      false)))

(defn run-claude-code
  "Execute Claude Code CLI in the specified worktree"
  [worktree-path task & {:keys [context-files timeout-ms]
                         :or {context-files [] timeout-ms 300000}}] ; 5 minute default
  (when-not (check-claude-code-available)
    (throw (ex-info "Claude Code CLI not available" {:worktree-path worktree-path})))

  (try
    (let [prompt (generate-task-prompt task context-files)
          temp-prompt-file (str worktree-path "/.todo-goblin-prompt.txt")]

      ;; Write prompt to temporary file
      (spit temp-prompt-file prompt)

      (println "Starting Claude Code AI assistant...")
      (println "Working directory:" worktree-path)
      (println "Task:" (:title task))

      ;; Execute Claude Code with the prompt
      (let [result (process/sh ["claude-code" "--prompt-file" temp-prompt-file]
                               {:dir worktree-path
                                :timeout timeout-ms})]

        ;; Clean up prompt file
        (io/delete-file temp-prompt-file true)

        (if (zero? (:exit result))
          {:success true
           :output (:out result)
           :message "AI task execution completed successfully"}
          {:success false
           :error (:err result)
           :output (:out result)
           :exit-code (:exit result)})))

    (catch java.util.concurrent.TimeoutException e
      {:success false
       :error "Claude Code execution timed out"
       :timeout true})

    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defn estimate-task-complexity
  "Estimate task complexity and suggest timeout"
  [task]
  (let [description-length (count (:description task))
        title-words (count (str/split (:title task) #"\s+"))
        complexity-indicators (+
                               (if (> description-length 500) 2 0)
                               (if (> title-words 10) 1 0)
                               (if (str/includes? (str/lower-case (:description task)) "test") 1 0)
                               (if (str/includes? (str/lower-case (:description task)) "refactor") 2 0))]
    (cond
      (< complexity-indicators 2) {:complexity :simple :timeout-ms 180000} ; 3 minutes
      (< complexity-indicators 4) {:complexity :medium :timeout-ms 300000} ; 5 minutes  
      :else {:complexity :complex :timeout-ms 600000}))) ; 10 minutes

(defn run-claude-code-adaptive
  "Run Claude Code with adaptive timeout based on task complexity"
  [worktree-path task & {:keys [context-files]}]
  (let [{:keys [complexity timeout-ms]} (estimate-task-complexity task)]
    (println (str "Estimated task complexity: " (name complexity)
                  " (timeout: " (/ timeout-ms 60000) " minutes)"))
    (run-claude-code worktree-path task
                     :context-files context-files
                     :timeout-ms timeout-ms)))

(defn get-relevant-files
  "Get a list of files that might be relevant to the task"
  [worktree-path task]
  (try
    (let [task-keywords (-> (:title task)
                            (str/lower-case)
                            (str/split #"\s+"))
          file-extensions #{"clj" "cljs" "cljc" "md" "org" "edn"}]

      ;; Simple heuristic: find files with names that match task keywords
      (->> (file-seq (io/file worktree-path))
           (filter #(.isFile %))
           (filter #(contains? file-extensions
                               (last (str/split (.getName %) #"\."))))
           (filter #(some (fn [keyword]
                            (str/includes? (str/lower-case (.getName %)) keyword))
                          task-keywords))
           (map #(.getPath %))
           (take 5))) ; Limit to 5 most relevant files
    (catch Exception e
      (println "Error finding relevant files:" (.getMessage e))
      [])))
