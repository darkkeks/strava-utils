from __future__ import annotations

import sys
from pathlib import Path

import click

DEFAULT_CONFIG = Path("stravahooks.json")


@click.group(
    context_settings={"help_option_names": ["-h", "--help"]},
    invoke_without_command=True,
)
@click.pass_context
def cli(ctx: click.Context) -> None:
    """Stravahooks service CLI."""
    if ctx.invoked_subcommand is None:
        click.echo(ctx.get_help())
        ctx.exit(0)


@cli.command()
@click.option(
    "--config",
    "config_path",
    type=click.Path(path_type=Path),
    default=DEFAULT_CONFIG,
    show_default=True,
    help="Path to the service configuration file.",
)
def init(config_path: Path) -> None:
    """Create a minimal config file stub."""
    config_path.parent.mkdir(parents=True, exist_ok=True)
    if config_path.exists():
        click.echo(f"Config already exists at {config_path}")
        return
    config_path.write_text("{}\n", encoding="utf-8")
    click.echo(f"Wrote empty config to {config_path}")


@cli.command()
@click.option(
    "--config",
    "config_path",
    type=click.Path(path_type=Path),
    default=DEFAULT_CONFIG,
    show_default=True,
    help="Path to the service configuration file.",
)
def run(config_path: Path) -> None:
    """Start the service (stub)."""
    click.echo(f"Starting stravahooks using config at {config_path} (stub).")


def main(argv: list[str] | None = None) -> int:
    cli.main(args=argv, prog_name="stravahooks", standalone_mode=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
