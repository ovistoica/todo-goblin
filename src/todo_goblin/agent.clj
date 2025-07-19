(ns todo-goblin.agent
  "Main agent orchestration for automated task processing"
  (:require [todo-goblin.config :as config]
            [todo-goblin.github :as github]
            [todo-goblin.todo :as todo]
            [todo-goblin.worktree :as worktree]
            [todo-goblin.ai :as ai]
            [todo-goblin.specs :as specs]
            [clojure.string :as str]))

(defn select-task
  "Select a task from available tasks using simple first-available strategy"
  [project-config active-prs active-worktrees available-tasks]
  (let [pr-branches (set (map :pr/branch active-prs))
        worktree-branches (set (map :branch active-worktrees))
        filtered-tasks (->> available-tasks
                            (filter (fn [task]
                                      (let [branch-name (todo/generate-task-branch-name task)]
                                        (and (not (contains? pr-branches branch-name))
                                             (not (contains? worktree-branches branch-name)))))))]
    (first filtered-tasks)))

(defn create-task-context
  "Create context map for task processing"
  [task project-config project-name]
  (let [branch-name (todo/generate-task-branch-name task)
        global-config (:global (config/load-config))
        worktree-path (worktree/get-worktree-path
                       (:worktree-base-path global-config)
                       project-name
                       (:id task))]
    {:task task
     :project-config project-config
     :project-name project-name
     :branch-name branch-name
     :worktree-path worktree-path
     :repo (:github/repo project-config)
     :repo-path (:cwd project-config)}))

(defn setup-worktree
  "Set up a new worktree for task processing"
  [context]
  (let [{:keys [repo-path worktree-path branch-name]} context]
    (println (str "Creating worktree: " worktree-path))
    (println (str "Branch: " branch-name))
    (let [result (worktree/create-worktree repo-path worktree-path branch-name)]
      (if (:success result)
        (do
          ;; Verify the worktree directory is accessible
          (Thread/sleep 100) ; Brief pause to ensure filesystem sync
          (if (and (.exists (clojure.java.io/file worktree-path))
                   (.isDirectory (clojure.java.io/file worktree-path)))
            result
            {:success false
             :error (str "Worktree directory not accessible after creation: " worktree-path)}))
        result))))

(defn create-draft-pr
  "Create a draft PR for the task"
  [context]
  (let [{:keys [task repo branch-name]} context
        title (github/pr-title-formats task :draft)
        body (str "Automated task implementation started by todo-goblin.\n\n"
                  "**Task:** " (:title task) "\n"
                  "**Task ID:** " (:id task) "\n"
                  "**Source:** " (name (:source task)) "\n\n"
                  "**Description:**\n" (:description task) "\n\n"
                  "This PR will be updated when the AI completes the task.")]
    (println (str "Creating draft PR: " title))
    (github/create-draft-pr repo branch-name title body)))

(defn execute-ai-task
  "Execute the AI task using Claude Code CLI"
  [context]
  (let [{:keys [task worktree-path]} context
        context-files (ai/get-relevant-files worktree-path task)]
    (println (str "Starting AI execution for: " (:title task)))
    (when (seq context-files)
      (println "Relevant context files found:")
      (doseq [file context-files]
        (println (str "  - " file))))
    (ai/run-claude-code-adaptive worktree-path task :context-files context-files)))

(defn finalize-task
  "Finalize task processing based on AI execution result"
  [context ai-result pr-result]
  (let [{:keys [task repo branch-name worktree-path repo-path]} context
        {:keys [pr-number]} pr-result]

    (if (:success ai-result)
      (do
        (println "AI task execution completed successfully")

        ;; Commit and push changes
        (let [commit-msg (str "🤖 Implement: " (:title task) "\n\nTask ID: " (:id task))
              push-result (worktree/commit-and-push worktree-path branch-name commit-msg)]

          (if (:success push-result)
            (do
              (println "Changes committed and pushed successfully")

              ;; Update PR title to indicate completion
              (let [new-title (github/pr-title-formats task :complete)
                    update-result (github/update-pr-title repo pr-number new-title)]

                (if (:success update-result)
                  (do
                    (println "PR title updated to indicate completion")
                    {:status :completed
                     :task task
                     :pr-number pr-number
                     :message "Task completed successfully"})
                  (do
                    (println "Warning: Failed to update PR title")
                    {:status :completed-with-warnings
                     :task task
                     :pr-number pr-number
                     :message "Task completed but PR title update failed"}))))

            (do
              (println "Error: Failed to commit/push changes")
              ;; Mark PR as failed
              (let [failed-title (github/pr-title-formats task :failed)]
                (github/update-pr-title repo pr-number failed-title))
              {:status :failed
               :task task
               :error "Failed to commit/push changes"
               :pr-number pr-number}))))

      (do
        (println "AI task execution failed")
        ;; Mark PR as failed
        (when pr-number
          (let [failed-title (github/pr-title-formats task :failed)]
            (github/update-pr-title repo pr-number failed-title)))
        {:status :failed
         :task task
         :error (:error ai-result)
         :pr-number pr-number}))))

(defn cleanup-on-failure
  "Clean up resources when task processing fails"
  [context reason]
  (let [{:keys [worktree-path branch-name repo-path]} context]
    (println (str "Cleaning up after failure: " reason))

    ;; Try to remove worktree
    (when worktree-path
      (worktree/cleanup-worktree worktree-path branch-name repo-path))

    {:status :failed
     :reason reason
     :cleanup-attempted true}))

(defn process-task
  "Process a single task end-to-end"
  [task project-config project-name]
  (let [context (create-task-context task project-config project-name)]
    (try
      (println (str "\n=== Processing Task: " (:title task) " ==="))

      ;; Step 1: Set up worktree
      (let [worktree-result (setup-worktree context)]
        (if-not (:success worktree-result)
          (cleanup-on-failure context (str "Worktree creation failed: " (:error worktree-result)))

          ;; Step 2: Execute AI task
          (let [ai-result (execute-ai-task context)]
            (if-not (:success ai-result)
              (cleanup-on-failure context (str "AI task execution failed: " (:error ai-result)))

              ;; Step 3: Commit and push changes
              (let [{:keys [branch-name worktree-path]} context
                    commit-msg (str "🤖 Implement: " (:title task) "\n\nTask ID: " (:id task))
                    push-result (worktree/commit-and-push worktree-path branch-name commit-msg)]

                (if-not (:success push-result)
                  (cleanup-on-failure context (str "Failed to commit/push changes: " (:error push-result)))

                  ;; Step 4: Create draft PR
                  (let [pr-result (create-draft-pr context)]
                    (if-not (:success pr-result)
                      (cleanup-on-failure context (str "PR creation failed: " (:error pr-result)))

                      ;; Step 5: Finalize task
                      (let [new-title (github/pr-title-formats task :complete)
                            {:keys [pr-number]} pr-result
                            update-result (github/update-pr-title (:repo context) pr-number new-title)]

                        (if (:success update-result)
                          (do
                            (println "Task completed successfully")
                            {:status :completed
                             :task task
                             :pr-number pr-number
                             :message "Task completed successfully"})
                          (do
                            (println "Warning: Failed to update PR title")
                            {:status :completed-with-warnings
                             :task task
                             :pr-number pr-number
                             :message "Task completed but PR title update failed"})))))))))))

      (catch Exception e
        (cleanup-on-failure context (.getMessage e))))))

(defn run-agent
  "Main agent entry point - run for a specific project"
  [project-name]
  (try
    (println (str "\n🤖 Starting todo-goblin agent for project: " project-name))

    ;; Load configuration
    (let [config (config/load-config)]
      (if (empty? config)
        (do
          (println "No configuration found. Run 'tgbl config init' first.")
          {:status :no-config})

        (let [project-config (config/get-project-config config project-name)]
          (if-not project-config
            (do
              (println (str "Project '" project-name "' not found in configuration."))
              {:status :project-not-found})

            (do
              ;; Check GitHub authentication
              (github/ensure-logged-in!)

              ;; Get current state
              (let [repo (:github/repo project-config)
                    active-prs (github/list-draft-prs repo)
                    active-worktrees (worktree/list-todo-goblin-worktrees (:cwd project-config))
                    available-tasks (todo/get-tasks project-config project-name
                                                    :github-list-issues-fn github/list-issues)]

                (println (str "Found " (count available-tasks) " available tasks"))
                (println (str "Found " (count active-prs) " active draft PRs"))
                (println (str "Found " (count active-worktrees) " active worktrees"))

                ;; Select and process task
                (if-let [selected-task (select-task project-config active-prs active-worktrees available-tasks)]
                  (do
                    (println (str "Selected task: " (:title selected-task)))
                    (let [result (process-task selected-task project-config project-name)]
                      (println (str "Task processing completed with status: " (:status result)))
                      result))

                  (do
                    (println "No available tasks to process")
                    {:status :no-tasks-available
                     :available-tasks (count available-tasks)
                     :active-prs (count active-prs)
                     :active-worktrees (count active-worktrees)}))))))))

    (catch Exception e
      (println (str "Agent error: " (.getMessage e)))
      {:status :error :error (.getMessage e)})))

(defn run-agent-for-current-directory
  "Run agent for project in current working directory"
  []
  (let [cwd (System/getProperty "user.dir")
        config (config/load-config)
        project-config (config/get-active-project-config config cwd)]
    (if project-config
      (let [project-name (->> (:projects config)
                              (filter #(= (-> % second :cwd) cwd))
                              first
                              first)]
        (run-agent project-name))
      (do
        (println "No todo-goblin configuration found for current directory.")
        (println "Run 'tgbl config init' to set up this project.")
        {:status :no-project-config}))))

(defn status
  "Show status of active agents and worktrees"
  []
  (let [config (config/load-config)]
    (if (empty? config)
      (do
        (println "No configuration found. Run 'tgbl config init' first.")
        {:status :no-config})

      (do
        (println "📊 Todo-goblin Status Report")
        (println "==========================")

        (doseq [[project-name project-config] (:projects config)]
          (println (str "\n🔧 Project: " project-name))
          (println (str "   Repository: " (:github/repo project-config)))
          (println (str "   Directory: " (:cwd project-config)))

          (try
            (let [repo (:github/repo project-config)
                  active-prs (github/list-draft-prs repo)
                  active-worktrees (worktree/list-todo-goblin-worktrees (:cwd project-config))
                  available-tasks (todo/get-tasks project-config project-name
                                                  :github-list-issues-fn github/list-issues)]

              (println (str "   📋 Available tasks: " (count available-tasks)))
              (println (str "   🔄 Active draft PRs: " (count active-prs)))
              (println (str "   🌿 Active worktrees: " (count active-worktrees)))

              (when (seq active-prs)
                (println "   Draft PRs:")
                (doseq [pr active-prs]
                  (println (str "     - " (:pr/title pr) " (#" (:pr/number pr) ")"))))

              (when (seq active-worktrees)
                (println "   Active worktrees:")
                (doseq [wt active-worktrees]
                  (println (str "     - " (:path wt) " (" (:branch wt) ")")))))

            (catch Exception e
              (println (str "   ❌ Error checking status: " (.getMessage e))))))

        {:status :ok}))))
