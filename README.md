# Java Dependency Parser

This tool uses the nice Java Parser library to parse imports out of java code.

Because the Java Parser parses the raw java code, we do not require the code to compile.

It can dump a simple JSON structure containing information about the parsed files
and their imports.

It can also detect and print cycles in the project classes.

## Examples

After building with

    ./gradlew build

you can run

    java -jar java-dependency.jar --json .

to see the dependencies within this repository.

To see all the cycles in the project run

    java -jar java-dependency.jar --cycles .

(There won't be any.)

## Using the JSON

You can use the JSON file to load the dependency graph into neo4j using the
following Cypher Script:

    WITH {json} as document
	UNWIND document.files AS file
	UNWIND file.types AS type
	UNWIND file.imports AS import
	MERGE (f:File {name: file.file})
	MERGE (t:Type {name: type})
	MERGE (i:Type {name: import})
	MERGE (f)-[:CONTAINS]->(t)
	MERGE (t)-[:IMPORTS]->(i)

(Which is probably not the smartest way to write it, but it works.)

Once the data is in your neo4j instance, you can find (and view) cycles with:

    MATCH (n)-[IMPORT*..n]->(n)
    RETURN n

