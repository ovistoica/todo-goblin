(ns todo-goblin.specs
  "Data specifications for todo-goblin core data structures")

;; Core data structure constructors and validators

(defn make-task
  "Create a task map with required fields"
  [{:keys [id title description source source-id status project-name]
    :or {status :todo}}]
  (cond-> {:id id
           :title title
           :description description
           :source source
           :source-id source-id
           :status status
           :project-name project-name}
    :always (assoc :created-at (java.time.Instant/now))))

(defn make-pull-request
  "Create a pull request map"
  [{:keys [pr-number pr-title pr-state pr-is-draft pr-branch pr-url project-name]}]
  {:pr/number pr-number
   :pr/title pr-title
   :pr/state pr-state
   :pr/is-draft pr-is-draft
   :pr/branch pr-branch
   :pr/url pr-url
   :project-name project-name})

(defn make-config
  "Create a configuration map"
  [global-config projects]
  {:global global-config
   :projects projects})
