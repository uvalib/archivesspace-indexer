package edu.virginia.lib.indexing.helpers;

import java.util.ArrayList;
import java.util.List;

public class KeyValues
{
    String key;
    List<String> values;
    
    public KeyValues(String key, String... values)
    {
        this.key = key;
        this.values = new ArrayList<>();
        for (String value : values)
        {
            this.values.add(value);
        }
    }
    
    public String getKey()
    {
        return key;
    }
    
    public List<String> getValues()
    {
        return values;
    }
}
