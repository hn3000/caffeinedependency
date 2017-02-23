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

(There won't be any, except for the example classes A and B.)

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

## Github top-100 script

To see the cycles from a bunch of github java projects I used the following code:


	# run this code one level up from this project

	# search
    curl "https://api.github.com/search/repositories?q=language:Java+stars:>1000&sort=stars&per_page=100" > most-starred-java.json

	# create folder where the code from github will be put
	mkdir projects
    # get the code
    node -e 'require("./most-starred-java.json").items.map(x => x.clone_url).forEach(x => console.log(x))'|(cd projects && xargs -n1 git clone --depth 1 --single-branch )

    # analyze the code
    # -Xss32m was necessary in order to parse all of the projects
    export JARDIR=$(cd caffeinedependency/build/libs && pwd)
	(
    	java -jar ${JARDIR}/java-dependencies.jar --csvheader
    	cd projects
    	for i in *
    	do
        	(>&2 echo $i)
        	java -Xmx128m -Xss32m -jar ${JARDIR}/java-dependencies.jar --cyclescsv $i
    	done
	) > results.txt 2>errors.txt

