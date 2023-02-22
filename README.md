# blobcas

`blobcas` is a filesystem-backed blob storage providing compare-and-set semantics.

Compare and set is useful for concurrent mutation and conflict management (assuming that clients are cooperating). See API example below.

[Deployment](#deployment)


## API

### Create a new blob

Creating a blob requires the admin key that is being generated on startup. Note that this is considered a "manual" operation usually done by humans.

```shell
$ echo "Hello world" | http POST localhost:8080?admin-key=YU9hF99W9PViTEfRzyWrIDFnwIpx2Qj8
HTTP/1.1 201 Created
Content-Type: text/plain
Date: Sat, 18 Feb 2023 13:37:21 GMT
Location: /HfuGPqUFvkcQcNzSxLfoQKCKP
Transfer-Encoding: chunked

Created HfuGPqUFvkcQcNzSxLfoQKCKP
```


### Retrieve a blob

Retrieving a blob is straight forward by using its id.

```shell
$ http localhost:8080/HfuGPqUFvkcQcNzSxLfoQKCKP
HTTP/1.1 200 OK
Content-Length: 12
Content-Type: application/octet-stream
Date: Sat, 18 Feb 2023 13:38:59 GMT

Hello world
````


### Update a blob

Because of the compare-and-set semantics a naive update will fail with `409 Conflict`:

```shell
$ echo "Hello universe!" | http PUT localhost:8080/HfuGPqUFvkcQcNzSxLfoQKCKP
HTTP/1.1 409 Conflict
Content-Type: text/plain
Date: Sat, 18 Feb 2023 13:41:17 GMT
Transfer-Encoding: chunked

Conflict
```

To make the update go through, you'll need to specify what you are intending to replace. For this we'll compute the SHA-256 sum of the content that we stored earlier and then provide it with the update:

```shell
$ echo "Hello world" | sha256sum
1894a19c85ba153acbf743ac4e43fc004c891604b26f8c69e1e83ea2afc7c48f  -

$ echo "Hello universe!" | http PUT localhost:8080/HfuGPqUFvkcQcNzSxLfoQKCKP?replaces=1894a19c85ba153acbf743ac4e43fc004c891604b26f8c69e1e83ea2afc7c48f
HTTP/1.1 200 OK
Content-Type: text/plain
Date: Sat, 18 Feb 2023 13:43:20 GMT
Transfer-Encoding: chunked

Stored
```

In a real-world scenario the client would re retrieve the blob, merge with its own local data, and try to update again. This process can be repeated until the update succeeds.


## Limitations

 * You cannot run two instances of the server off the same backing file system. (Concurrent modification is avoided on the process level.)
 * The blob id is securely generated for you and cannot be changed.


## Development

 * Run `make` to launch an nREPL (evaluate forms at the bottom of `core.clj`)
 * Run `make run` to launch the server


## Deployment

 * Run `docker build . -t blobcas` to build an image
   * To start on port 8080 backed by `/tmp`, run `docker run -p 8080:8080 -v /tmp:/data blobcas`
