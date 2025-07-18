(ns todo-goblin.core
  "Main CLI entry point for todo-goblin"
  (:require [todo-goblin.config :as config]
            [todo-goblin.agent :as agent]
            [clojure.string :as str]
            [clojure.pprint :as pprint])
  (:gen-class))

(defn print-usage
  "Print CLI usage information"
  []
  (println "Todo-goblin - Automated AI task completion")
  (println "Usage: tgbl <command> [options]")
  (println)
  (println "Commands:")
  (println "  config init          Initialize configuration for current project")
  (println "  config show          Show current configuration")
  (println "  status              Show status of active agents and worktrees")
  (println "  run [project-name]   Run agent for specific project")
  (println "  run                 Run agent for current directory")
  (println "  help                Show this help message")
  (println)
  (println "Examples:")
  (println "  tgbl config init")
  (println "  tgbl config show")
  (println "  tgbl status")
  (println "  tgbl run my-project")
  (println "  tgbl run"))

(defn handle-config-command
  "Handle config subcommands"
  [subcommand]
  (case subcommand
    "init" (config/init-config)
    "show" (let [config (config/load-config)]
             (config/show-config config))
    (do
      (println "Unknown config command:" subcommand)
      (println "Available: init, show")
      (System/exit 1))))

(defn handle-run-command
  "Handle run command with optional project name"
  [project-name]
  (if project-name
    (agent/run-agent project-name)
    (agent/run-agent-for-current-directory)))

(defn parse-args
  "Parse command line arguments"
  [args]
  (cond
    (empty? args)
    {:command :help}

    (= (first args) "help")
    {:command :help}

    (= (first args) "config")
    {:command :config :subcommand (second args)}

    (= (first args) "status")
    {:command :status}

    (= (first args) "run")
    {:command :run :project-name (second args)}

    :else
    {:command :unknown :args args}))

(defn -main
  "Main CLI entry point"
  [& args]
  (try
    (let [parsed (parse-args args)]
      (case (:command parsed)
        :help
        (print-usage)

        :config
        (handle-config-command (:subcommand parsed))

        :status
        (agent/status)

        :run
        (handle-run-command (:project-name parsed))

        :unknown
        (do
          (println "Unknown command:" (str/join " " (:args parsed)))
          (println)
          (print-usage)
          (System/exit 1))))

    (catch Exception e
      (println "Error:" (.getMessage e))
      (when (System/getProperty "todo-goblin.debug")
        (.printStackTrace e))
      (System/exit 1))))

;; Legacy functions for backwards compatibility
(defn read-config
  "Read configuration from ~/.config/todo-goblin/config.edn"
  []
  (config/load-config))

(defn init
  "Initialize todo-goblin configuration"
  []
  (config/init-config))

;; Additional utility functions
(defn validate-project-setup
  "Validate that a project is properly set up for todo-goblin"
  [project-name]
  (let [config (config/load-config)
        project-config (config/get-project-config config project-name)]
    (cond
      (empty? config)
      {:valid false :error "No configuration found. Run 'tgbl config init' first."}

      (not project-config)
      {:valid false :error (str "Project '" project-name "' not found in configuration.")}

      (not (.exists (java.io.File. (:cwd project-config))))
      {:valid false :error (str "Project directory does not exist: " (:cwd project-config))}

      :else
      {:valid true :config project-config})))

(defn list-projects
  "List all configured projects"
  []
  (let [config (config/load-config)]
    (if (empty? config)
      (println "No configuration found. Run 'tgbl config init' first.")
      (do
        (println "Configured projects:")
        (doseq [[project-name project-config] (:projects config)]
          (println (str "  " project-name " -> " (:github/repo project-config))))))))
