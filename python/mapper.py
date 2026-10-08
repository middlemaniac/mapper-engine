try:
    from .data_mapper import DataMapper
    from .orchestrator import main
except ImportError:
    from data_mapper import DataMapper
    from orchestrator import main


if __name__ == "__main__":
    raise SystemExit(main())
