# garden-template

A template for [application.garden](https://application.garden) projects.

Check out the [docs](shttps://docs.apps.garden/#project-templates) for more information.

## Usage

This gets automatically used by `garden init`.

You can use a specific commit from this repo using `garden init --git/sha <commit>`.

If you want you can also use it with [neil](https):

    $ neil new io.github.nextjournal/garden-template

or with

[deps-new](https://github.com/seancorfield/deps-new):

    $ clojure -Tnew create :template io.github.nextjournal/garden-template :name myusername/mycoolproject

Assuming you have installed `deps-new` as your `new` "tool" via:

```bash
clojure -Ttools install-latest :lib io.github.seancorfield/deps-new :as new
```
    



## License

Copyright © 2024 Nextjournal
