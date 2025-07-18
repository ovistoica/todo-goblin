# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test Commands

### Running Tests
```bash
# Run all tests using kaocha
clojure -M:test

# Run specific test namespace
clojure -M:test --focus todo-goblin.config-test

# Run tests with babashka (development)
bb test
```

### Development
```bash
# Start REPL with test dependencies
clojure -M:test:dev

# Start babashka REPL
bb repl

# Run individual namespace in REPL
bb -e "(require '[todo-goblin.core :as core]) (core/init)"
```

### Building
```bash
# Compile to uberjar (when implemented)
clojure -M:uberjar

# Run via babashka
bb src/todo_goblin/core.clj
```

## Project Architecture

**Todo-Goblin** is an AI-powered task automation system that processes TODO items from org files or GitHub issues and creates pull requests with AI-generated solutions.

### Core Architecture
- **Data-centric design**: All operations work with standardized Clojure maps
- **Functional approach**: Pure functions with external effects isolated
- **CLI-first**: Built for command-line automation and cron job execution
- **Worktree isolation**: Each AI task runs in its own git worktree

### Key Namespaces

**`todo-goblin.core`** - CLI entry point and command dispatch
- Main CLI commands: `config init`, `config show`, `status`, `run`
- Uses `babashka.cli` for argument parsing

**`todo-goblin.config`** - Configuration management
- Loads/saves `~/.config/todo-goblin/config.edn`
- Handles project-specific settings and global configuration
- Interactive setup via `init-config`

**`todo-goblin.agent`** - Core orchestration logic
- Main automation loop: `run-agent` and `process-task`
- Manages task selection, worktree creation, AI invocation, and PR updates
- Handles the complete lifecycle from task selection to completion

**`todo-goblin.todo`** - TODO source parsing
- Supports org-mode files and GitHub issues
- Standardizes tasks into `:todo-goblin/task` maps
- Filters available tasks based on existing PRs and worktrees

**`todo-goblin.github`** - GitHub CLI wrapper
- All GitHub operations via `gh` CLI using `babashka.process`
- PR management: create drafts, update titles, list open PRs
- Issue querying with label filtering

**`todo-goblin.worktree`** - Git worktree management
- Creates isolated development environments for AI tasks
- Manages worktree lifecycle and cleanup
- Prevents conflicts between concurrent AI agents

**`todo-goblin.ai`** - Claude Code CLI interface
- Executes AI tasks within worktrees
- Manages prompt construction and output handling

### Data Structures

**Configuration Map**:
```clojure
{:global {:worktree-base-path "~/.todo-goblin/worktrees"}
 :projects {"project-name" {:cwd "/path/to/project"
                           :github/repo "user/repo"
                           :todo/type :org ; or :github
                           :todo/file "todo.org"}}}
```

**Task Map**:
```clojure
{:id "unique-task-id"
 :title "Task Title"
 :description "Task description"
 :source :org ; or :github
 :status :todo
 :project-name "project-name"
 :assigned-pr {:pr/number 123 :pr/branch "feature/branch"}}
```

### Testing Strategy
- **Significant Other File Pattern**: Each `src/foo/bar.clj` has `test/foo/bar_test.clj`
- **Mocked External Dependencies**: Uses `with-redefs` to mock `babashka.process/sh` calls
- **Pure Function Focus**: Tests emphasize data transformations and business logic
- **Integration Tests**: Limited to orchestration flows with mocked CLI interactions

### Development Workflow
1. Use REPL-driven development for individual functions
2. Test external CLI interactions via mocked `sh` calls
3. Focus on data transformations and pure functions first
4. Integration testing through the agent orchestration layer

### Dependencies
- **Core**: `babashka.process` (CLI execution), `cheshire` (JSON parsing)
- **External Tools**: `gh` CLI (GitHub operations), `git` (worktree management)
- **AI Integration**: Claude Code CLI for task execution