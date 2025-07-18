# Todo-Goblin Project Summary

## Overview

**Todo-Goblin** is an AI-powered task automation system that processes TODO items from org files or GitHub issues and creates pull requests with AI-generated solutions. It operates as a CLI tool designed for automated execution (e.g., via cron jobs) and uses Claude Code CLI for AI task implementation.

## Project Architecture

### Core Philosophy
- **Data-centric design**: All operations work with standardized Clojure maps
- **Functional approach**: Pure functions with external effects isolated
- **CLI-first**: Built for command-line automation and cron job execution
- **Worktree isolation**: Each AI task runs in its own git worktree to prevent conflicts

### Key Components Flow
1. **Configuration** → Load project settings and TODO sources
2. **Task Discovery** → Parse org files or GitHub issues into standardized task maps
3. **Task Selection** → Choose available tasks (avoiding conflicts with existing PRs/worktrees)
4. **Worktree Setup** → Create isolated git worktree for AI development
5. **AI Execution** → Run Claude Code CLI to implement the task
6. **PR Management** → Create/update GitHub pull requests with AI changes

## Key File Paths

### Core Implementation (`src/todo_goblin/`)
- **`core.clj`** - CLI entry point and command dispatch (`config init`, `config show`, `status`, `run`)
- **`config.clj`** - Configuration management (`~/.config/todo-goblin/config.edn`)
- **`agent.clj`** - Main orchestration logic (task selection, worktree management, AI invocation)
- **`todo.clj`** - TODO source parsing (org-mode files, GitHub issues)
- **`github.clj`** - GitHub CLI wrapper (PR management, issue querying)
- **`worktree.clj`** - Git worktree management (isolation, cleanup)
- **`ai.clj`** - Claude Code CLI interface (prompt generation, execution, output handling)
- **`specs.clj`** - Clojure spec definitions for data validation

### Test Files (`test/todo_goblin/`)
- **`config_test.clj`** - Configuration loading/saving tests
- **`core_test.clj`** - CLI command tests
- **`todo_test.clj`** - TODO parsing and task generation tests

### Project Configuration
- **`deps.edn`** - Clojure dependencies and aliases
- **`bb.edn`** - Babashka configuration with `tgbl` task
- **`CLAUDE.md`** - Instructions for Claude Code AI assistant
- **`architecture.md`** - Detailed architecture documentation

## Dependencies

### Core Dependencies
- **`org.clojure/clojure 1.12.0`** - Core Clojure runtime
- **`babashka/process 0.6.23`** - CLI process execution (enhanced in recent changes)
- **`babashka/fs 0.5.26`** - File system operations
- **`cheshire/cheshire 5.12.0`** - JSON parsing for GitHub API responses
- **`hato/hato 1.1.0-SNAPSHOT`** - HTTP client (if needed)

### Test Dependencies
- **`lambdaisland/kaocha 1.91.1392`** - Test runner
- **`kaocha-noyoda/kaocha-noyoda 2019-06-03`** - Enhanced test assertions

### External Tool Dependencies
- **`gh` CLI** - GitHub operations (PRs, issues, authentication)
- **`git`** - Worktree management and version control
- **`claude` CLI** - Claude Code AI for task implementation

## Data Structures

### Configuration Map (`:todo-goblin/config`)
```clojure
{:global {:worktree-base-path "~/.todo-goblin/worktrees"}
 :projects {"project-name" {:cwd "/path/to/project"
                           :github/repo "user/repo"
                           :todo/type :org ; or :github
                           :todo/file "todo.org"}}}
```

### Task Map (`:todo-goblin/task`)
```clojure
{:id "unique-task-id"
 :title "Task Title"
 :description "Task description"
 :source :org ; or :github
 :status :todo
 :project-name "project-name"
 :assigned-pr {:pr/number 123 :pr/branch "feature/branch"}}
```

### Pull Request Map (`:todo-goblin/pull-request`)
```clojure
{:pr/number 123
 :pr/title "WIP: Implement feature X"
 :pr/state :open
 :pr/is-draft true
 :pr/branch "feature/task-branch"
 :pr/url "https://github.com/user/repo/pull/123"}
```

## Available Tools/APIs

### CLI Commands
```bash
# Initialize configuration
tgbl config init

# Show current configuration
tgbl config show

# Show project status
tgbl status

# Run agent for specific project
tgbl run <project-name>

# Run agent for current directory
tgbl run
```

### Babashka Integration
```bash
# Run via babashka task
bb tgbl <command>

# Run tests
bb test                    # Development tests
clojure -M:test           # Full test suite
```

### Core API Functions

#### Configuration (`todo-goblin.config`)
```clojure
(load-config)                          ; Load from ~/.config/todo-goblin/config.edn
(save-config! config)                  ; Save configuration
(get-project-config config project)   ; Get project-specific config
(init-config)                          ; Interactive configuration setup
```

#### Task Management (`todo-goblin.todo`)
```clojure
(get-tasks project-config)             ; Get tasks from org files or GitHub
(generate-task-branch-name task)       ; Generate git branch name
(filter-available-tasks tasks prs)     ; Filter out conflicting tasks
```

#### GitHub Integration (`todo-goblin.github`)
```clojure
(list-open-prs repo)                   ; List open pull requests
(create-draft-pr repo branch title)    ; Create draft PR
(update-pr-title repo pr-number title) ; Update PR title
(list-issues repo label)               ; List GitHub issues with label
```

#### Worktree Management (`todo-goblin.worktree`)
```clojure
(create-worktree repo-path worktree-path branch) ; Create isolated worktree
(remove-worktree worktree-path)                  ; Clean up worktree
(list-active-worktrees base-path)                ; List managed worktrees
```

#### AI Integration (`todo-goblin.ai`)
```clojure
(run-claude-code worktree-path task)             ; Execute Claude Code CLI
(run-claude-code-adaptive worktree-path task)    ; Adaptive timeout based on complexity
(generate-task-prompt task context-files)       ; Generate AI prompt
```

## Recent Enhancements

### AI Output Handling (Latest Changes)
The AI integration has been enhanced to properly capture and display Claude Code CLI output:

- **Output Capture**: Both stdout and stderr are captured using `:out :string` and `:err :string`
- **Console Display**: AI output is displayed with clear section delimiters for visibility
- **Result Structure**: Enhanced return maps include both `:output` and `:stderr` fields
- **Error Handling**: Detailed error output with exit codes and debug information

Example usage:
```clojure
(ai/run-claude-code "/path/to/worktree" task)
; Returns: {:success true :output "..." :stderr "..." :message "..."}
```

## Implementation Patterns

### Testing Strategy
- **Significant Other File Pattern**: Each `src/foo/bar.clj` has `test/foo/bar_test.clj`
- **Mocked External Dependencies**: Uses `with-redefs` to mock `babashka.process/sh` calls
- **Pure Function Focus**: Tests emphasize data transformations and business logic
- **Integration Tests**: Limited to orchestration flows with mocked CLI interactions

### Error Handling
- Functions return structured maps with `:success` boolean and error details
- External CLI calls are wrapped in try-catch blocks
- Cleanup functions for worktrees and resources on failure

### Data Validation
- Clojure spec definitions in `todo-goblin.specs`
- Validation at API boundaries
- Structured error messages with context

## Development Workflow

### Setup
1. Install dependencies: `gh` CLI, `git`, `claude` CLI
2. Run `tgbl config init` to set up project configuration
3. Start REPL: `clojure -M:test:dev` or `bb repl`

### Testing
```bash
clojure -M:test                    # Run all tests
clojure -M:test --focus namespace  # Run specific tests
bb test                            # Development/babashka tests
```

### REPL Development
```clojure
(require '[todo-goblin.core :as core])
(core/init)                        ; Initialize for development
```

## Extension Points

### Adding New TODO Sources
1. Extend `todo-goblin.todo/get-tasks` multimethod
2. Add parsing logic for new format
3. Return standardized `:todo-goblin/task` maps

### Custom AI Backends
1. Implement new functions in `todo-goblin.ai` namespace
2. Follow existing patterns for prompt generation and output handling
3. Maintain compatibility with existing result structures

### Additional GitHub Operations
1. Extend `todo-goblin.github` with new `gh` CLI wrappers
2. Add JSON parsing for new GitHub API responses
3. Update data structures in `todo-goblin.specs`

### Configuration Extensions
1. Add new fields to configuration schema in `todo-goblin.specs`
2. Update `init-config` prompts in `todo-goblin.config`
3. Handle backward compatibility in `load-config`

## Architecture Principles

### Data Flow
```
Config → Task Discovery → Task Selection → Worktree Setup → AI Execution → PR Management
```

### Functional Composition
- Pure functions for data transformation
- Side effects isolated to specific namespaces (`github`, `worktree`, `ai`)
- Threading macros for data pipeline operations

### External Integration
- All external tool interactions via `babashka.process/sh`
- Structured error handling with fallback strategies
- Timeout management for long-running operations

This architecture supports reliable, automated task processing while maintaining clear separation of concerns and testability.