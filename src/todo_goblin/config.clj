(ns todo-goblin.config
  "Configuration management for todo-goblin"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [todo-goblin.specs :as specs]))

(def default-config-dir
  (str (System/getProperty "user.home") "/.config/todo-goblin"))

(def default-config-file
  (str default-config-dir "/config.edn"))

(defn ensure-config-dir!
  "Ensure the config directory exists"
  [config-dir]
  (let [dir (io/file config-dir)]
    (when-not (.exists dir)
      (.mkdirs dir))))

(defn load-config
  "Load configuration from config file, return empty map if not found"
  ([]
   (load-config default-config-file))
  ([config-file]
   (try
     (when (.exists (io/file config-file))
       (-> config-file slurp edn/read-string))
     (catch Exception e
       (println "Error reading config:" (.getMessage e))
       {}))))

(defn save-config!
  "Save configuration to file"
  ([config]
   (save-config! config default-config-file))
  ([config config-file]
   (ensure-config-dir! (str (io/file config-file) "/../"))
   (spit config-file (with-out-str (pprint/pprint config)))))

(defn get-project-config
  "Get configuration for a specific project"
  [config project-name]
  (get-in config [:projects project-name]))

(defn get-active-project-config
  "Get project config based on current working directory"
  [config cwd]
  (->> (:projects config)
       (filter (fn [[_ project-config]]
                 (= (:cwd project-config) cwd)))
       first
       second))

(defn add-project-config!
  "Add or update a project configuration"
  [config project-name project-config]
  (assoc-in config [:projects project-name] project-config))

(defn show-config
  "Pretty print configuration"
  [config]
  (if (empty? config)
    (println "No configuration found. Run 'tgbl config init' to set up.")
    (pprint/pprint config)))

(defn prompt-input
  "Prompt user for input with validation"
  [prompt & {:keys [default validator]}]
  (print (str prompt (when default (str " [" default "]")) ": "))
  (flush)
  (let [input (str/trim (read-line))
        value (if (empty? input) default input)]
    (if (and validator (not (validator value)))
      (do
        (println "Invalid input, please try again.")
        (prompt-input prompt :default default :validator validator))
      value)))

(defn init-config
  "Interactive configuration initialization"
  []
  (println "Initializing todo-goblin configuration...")
  (println "This will set up a new project configuration.")

  (let [project-name (prompt-input "Project name"
                                   :validator #(not (str/blank? %)))
        cwd (prompt-input "Project directory (absolute path)"
                          :default (System/getProperty "user.dir")
                          :validator #(.exists (io/file %)))
        repo (prompt-input "GitHub repository (user/repo format)"
                           :validator #(re-matches #"^[\w.-]+/[\w.-]+$" %))
        todo-type (keyword (prompt-input "TODO source type (org/github)"
                                         :default "org"
                                         :validator #(contains? #{"org" "github"} %)))
        todo-file (when (= todo-type :org)
                    (prompt-input "TODO file path (relative to project)"
                                  :default "todo.org"))
        github-label (when (= todo-type :github)
                       (prompt-input "GitHub issues label for TODOs"
                                     :default "todo"))

        existing-config (load-config)
        global-config (or (:global existing-config)
                          {:worktree-base-path (str (System/getProperty "user.home") "/.todo-goblin/worktrees")})

        project-config (cond-> {:cwd cwd
                                :github/repo repo
                                :cron "0 9 * * *" ; Default: 9 AM daily
                                :todo/type todo-type}
                         todo-file (assoc :todo/file todo-file)
                         github-label (assoc :github/issues-label github-label))

        new-config (-> existing-config
                       (assoc :global global-config)
                       (add-project-config! project-name project-config))]

    (println "\nConfiguration to be saved:")
    (show-config new-config)

    (when (= "y" (str/lower-case (prompt-input "\nSave this configuration? (y/n)" :default "y")))
      (save-config! new-config)
      (println (str "Configuration saved to " default-config-file))
      new-config)))
