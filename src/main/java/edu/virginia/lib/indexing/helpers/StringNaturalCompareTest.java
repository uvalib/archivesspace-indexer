package edu.virginia.lib.indexing.helpers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StringNaturalCompareTest {

    public static void main(String[] args) {
        List<String> listAsRead = new ArrayList<String>();
        List<String> listSorted = new ArrayList<String>();
        BufferedReader reader;
	boolean check = false;
	if (args.length >= 1 && args[0].equals("-c"))
        {
            check=true;
        }
        reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        try {
            while ((line = reader.readLine()) != null)
            {
                listAsRead.add(line);
                listSorted.add(line);
            }
            reader.close();
        } 
        catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        Collections.sort(listSorted, new StringNaturalCompare());
        for (int i = 0; i < listAsRead.size(); i++)
        {
            String asRead = listAsRead.get(i);
            String sorted = listSorted.get(i);
            if (check && !asRead.equals(sorted)) System.exit(-1);
            if (!check)
            {
                System.out.println(sorted);
            }
        }
        System.exit(0);
    }
}

