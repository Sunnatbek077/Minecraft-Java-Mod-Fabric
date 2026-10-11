package com.sunnatbek.villagerciv.sim;

import java.util.Collection;

final class CitizenNames {
	private static final String[] NAMES = {
			"Alder", "Anselm", "Bram", "Brenna", "Cedric", "Corin", "Dara", "Edda", "Edmund", "Elva",
			"Fenn", "Freya", "Garrick", "Greta", "Hale", "Hilda", "Ivo", "Isolde", "Jorah", "Keld",
			"Kira", "Leif", "Liesel", "Mabel", "Magnus", "Nell", "Odo", "Orla", "Osric", "Perrin",
			"Quill", "Rowan", "Runa", "Sable", "Soren", "Tamsin", "Theo", "Ulric", "Vesna", "Wren",
			"Ada", "Adela", "Aldous", "Aldric", "Alma", "Alric", "Ansel", "Arden", "Arlo", "Arne",
			"Asa", "Astrid", "Aubrey", "Auden", "Averil", "Aylwin", "Baldric", "Barnaby", "Bastian", "Beatrix",
			"Benno", "Berit", "Bertram", "Birgit", "Blythe", "Bodil", "Boris", "Brandt", "Briar", "Brigid",
			"Bryn", "Cade", "Calla", "Caspar", "Cato", "Cecily", "Celia", "Clement", "Clove", "Colm",
			"Conrad", "Cora", "Cormac", "Cosima", "Cyril", "Dagny", "Dalia", "Damaris", "Darby", "Delia",
			"Dermot", "Desmond", "Dinah", "Dorian", "Dorothea", "Drogo", "Dulcie", "Dunstan", "Eamon", "Ebba",
			"Edith", "Edric", "Egon", "Eira", "Eldon", "Eleri", "Elias", "Elspeth", "Emeric", "Emlyn",
			"Enid", "Erland", "Ermin", "Esme", "Estrid", "Ethel", "Etta", "Evander", "Ewan", "Faye",
			"Felix", "Fergus", "Fern", "Finn", "Fiona", "Flora", "Florian", "Flint", "Frida", "Fulk",
			"Gable", "Galen", "Gareth", "Gemma", "Gerda", "Gideon", "Gilda", "Giles", "Godric", "Golda",
			"Gunnar", "Gwen", "Hadrian", "Hakon", "Halden", "Hanne", "Harald", "Harlan", "Hattie", "Hazel",
			"Hedda", "Helga", "Henrik", "Hester", "Holt", "Hugo", "Ida", "Idris", "Ilse", "Inga",
			"Ingrid", "Iona", "Irma", "Isak", "Ivar", "Ivy", "Jago", "Jarl", "Jasper", "Jem",
			"Jessamy", "Joss", "Jude", "Juniper", "Kai", "Karsten", "Kasper", "Katla", "Kell", "Kenna",
			"Kester", "Kit", "Knut", "Lark", "Lars", "Laurel", "Leander", "Lena", "Leofric", "Linnea",
			"Linus", "Lioba", "Lorcan", "Lotte", "Lucan", "Ludo", "Luned", "Lyle", "Mads", "Maeve",
			"Maida", "Malin", "Marit", "Marta", "Matthias", "Maud", "Merle", "Merrick", "Mina", "Mirren",
			"Moira", "Morna", "Mungo", "Myrtle", "Nadia", "Neve", "Nico", "Nils", "Ninian", "Noll",
			"Nora", "Norbert", "Odalys", "Odette", "Olaf", "Oleander", "Olwen", "Opal", "Oren", "Orin",
			"Oswin", "Otto", "Ottilie", "Pascal", "Peregrine", "Petra", "Philippa", "Piers", "Pippa", "Posy",
			"Prudence", "Quentin", "Ragna", "Ralf", "Ramsey", "Rafe", "Reeve", "Reinhild", "Renata", "Rhea",
			"Rhys", "Rikard", "Robin", "Roderic", "Rolf", "Romilly", "Rosalind", "Roscoe", "Rue", "Rufus",
			"Saga", "Sander", "Saskia", "Selwyn", "Seren", "Sibyl", "Sidony", "Sigrid", "Silas", "Silke",
			"Solveig", "Stellan", "Sten", "Sunniva", "Svea", "Swithin", "Sylvie", "Tabitha", "Talbot", "Tansy",
			"Tarn", "Tavish", "Thea", "Thora", "Thorne", "Tilda", "Tobias", "Torin", "Tove", "Tristan",
			"Tyra", "Ulla", "Una", "Ursula", "Valda", "Vale", "Varda", "Verity", "Vidar", "Viggo",
			"Wendeline", "Wilf", "Wilhelmina", "Willa", "Winifred", "Wolfram", "Wystan", "Yara", "Ysolde", "Yorick",
			"Yrsa", "Zelda", "Zenobia", "Zora"
	};

	private CitizenNames() {
	}

	static String pick(IdGenerator random) {
		return NAMES[random.nextInt(NAMES.length)];
	}

	/** A name nobody in the group answers to yet, as long as a few tries find one. */
	static String pick(IdGenerator random, Collection<Citizen> group) {
		String name = pick(random);
		for (int attempt = 0; attempt < 8 && taken(name, group); attempt++) {
			name = pick(random);
		}
		return name;
	}

	private static boolean taken(String name, Collection<Citizen> group) {
		for (Citizen citizen : group) {
			if (citizen.name().equals(name)) {
				return true;
			}
		}
		return false;
	}
}
