# Contributing to Cursorly

Thank you for your interest in improving Cursorly!

## Development Setup

1. Clone your fork of the repository:
   ```bash
   git clone https://github.com/mhsatgit/Cursorly.git
   cd Cursorly
   ```

2. Create and activate a Python virtual environment:
   ```powershell
   py -3 -m venv .venv
   .\.venv\Scripts\activate
   ```

3. Install development dependencies:
   ```powershell
   pip install -r requirements-dev.txt
   ```

4. Install Android Build Tools:

   Refer README [Running Tests & Building](README.md#running-tests--building)

## Running Tests

Run the full automated Python test suite before opening a pull request:
```powershell
python -m unittest discover tests -v
```

If modifying Android code under `android/`, also run the Android unit tests in Android Studio (or `gradle -p android testDebugUnitTest`). Ensure all tests pass and that any new features include corresponding test cases.

## Submitting Pull Requests

1. Create a feature branch (`git checkout -b feature/my-new-feature`).
2. Keep commits descriptive and focused.
3. Test touch and cursor responsiveness on a physical Windows device if modifying input injection or filtering code.
4. Open a pull request against the `main` branch.
