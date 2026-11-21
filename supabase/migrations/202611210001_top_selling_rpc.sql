CREATE OR REPLACE FUNCTION public.top_selling_items(p_limit integer DEFAULT 20)
 RETURNS TABLE (
     menu_item_id uuid,
     norm_name text,
     total_qty bigint,
     order_count bigint
 )
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
    SELECT
        oi.menu_item_id,
        CASE WHEN oi.menu_item_id IS NULL THEN lower(trim(oi.item_name)) ELSE '' END AS norm_name,
        coalesce(sum(greatest(oi.quantity, 1)), 0)::bigint AS total_qty,
        count(DISTINCT o.id)::bigint AS order_count
    FROM public.order_items oi
    JOIN public.orders o ON o.id = oi.order_id
    WHERE o.status IN ('confirmed', 'preparing', 'processing', 'ready', 'completed')
    GROUP BY oi.menu_item_id, CASE WHEN oi.menu_item_id IS NULL THEN lower(trim(oi.item_name)) ELSE '' END
    ORDER BY coalesce(sum(greatest(oi.quantity, 1)), 0) DESC, count(DISTINCT o.id) DESC
    LIMIT greatest(1, least(coalesce(p_limit, 20), 50));
$function$;

REVOKE ALL ON FUNCTION public.top_selling_items(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.top_selling_items(integer) TO anon, authenticated;
