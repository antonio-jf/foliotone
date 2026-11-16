# Mobile app that reads epub books in different languages.

This project involves creating an application for mobile devices.

## Main features

-   Use either Flutter or React native if you see fit.
-   The user should be able to load in files in epub format that the application will store in an application directory for later retrieval/resuming.
-   The application should save the progress status of the books and should resume reading by starting a page earlier to give the user context of the current status. This should be an option, and it must be deactivatable by the user in the settings.
-   The application should prompt the user for the language of the book upon opening it or infer it from the first few pages. This way the reader is able to read the book in the language that the book is written on.
-   The application's audio and interface should be accessible via Android auto, for an in-auto experience while driving.
-   The reader's interface should provide an overview of the current book section and the progress as a bar that the user can traverse using.
-   Give the user simple audio commands to stop, resume, start from the beginning of the section, etc.

## Considerations

-   Prompt the user for a storage location to save the books loaded, or a place that the users books are. Display the books using a `Home` interface with the books loaded by the user or the books found in the directory used as `Library`.

Initially, the application should support 5 languages: English, Italian, French, German, and Spanish. Though the user might be able to download other languages. For now, just make sure those 5 are available.
Implement any other features you see fit for an application of this type.

## Current development context

See the [development handoff](docs/DEVELOPMENT_HANDOFF.md) for the implemented architecture, recent changes, working build configuration, verification history, and remaining validation work.
